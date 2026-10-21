package com.kavach.caller.data.telemetry

import android.content.Context
import com.kavach.caller.data.diagnostics.LogEventCodec
import com.kavach.caller.data.diagnostics.SessionContext
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends diagnostics events to the operator's own Kavach server.
 *
 * This is the path that works at scale. Pushing to a user's personal Notion
 * database only ever works for the handful of people willing to create a Notion
 * integration and paste a token — at a hundred installs that is nobody, and the
 * rows would land in *their* workspace rather than yours.
 *
 * So: every install sends the same schema to one server, the server stores it,
 * and a server-side job writes it into the operator's Notion database using a
 * token that never leaves the server.
 *
 * What still holds true from the on-device rules:
 *  - off by default; nothing is uploaded until the user switches it on;
 *  - the same queue as the direct-Notion path, so an outage loses nothing;
 *  - the same redaction, because it sends the very same event lines.
 */
@Singleton
class TelemetryUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val session: SessionContext,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun queueFile(): File {
        val dir = File(context.filesDir, "notion-queue").apply { mkdirs() }
        return File(dir, QUEUE_FILE)
    }

    fun isConfigured(): Boolean =
        settings.snapshot.telemetryUploadEnabled &&
            settings.snapshot.backendBaseUrl.isNotBlank()

    /**
     * Send queued events to the server.
     *
     * @return (sent, failure) — `failure` is a short reason when the run stopped
     *   early. Events the server rejects are dropped so one bad build cannot jam
     *   the queue for every other install.
     */
    suspend fun flush(limit: Int = MAX_PER_FLUSH): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext 0 to null

            val file = queueFile()
            if (!file.exists()) return@withContext 0 to null

            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            if (lines.isEmpty()) return@withContext 0 to null

            val batch = lines.take(limit)
            val rest = lines.drop(batch.size).toMutableList()

            val events = batch.mapNotNull { LogEventCodec.decode(it) }
            if (events.isEmpty()) {
                file.writeText(rest.joinToString("\n", postfix = if (rest.isEmpty()) "" else "\n"))
                return@withContext 0 to null
            }

            val payload = buildJsonObject {
                // The random per-install pseudonym, not the account id. Sending
                // the account id here would have leaked a real identifier.
                put("user_code", JsonPrimitive(session.userCode))
                put(
                    "events",
                    JsonArray(
                        events.map { event ->
                            // The queue line already *is* the schema event, so it
                            // is forwarded as-is rather than re-serialised into a
                            // second shape that could drift.
                            json.parseToJsonElement(
                                LogEventCodec.encode(event, settings.envelopeForUpload(), null),
                            ) as JsonObject
                        },
                    ),
                )
            }

            val base = settings.snapshot.backendBaseUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/v1/telemetry/events")
                .header("Content-Type", "application/json")
                .apply {
                    settings.snapshot.backendToken
                        .takeIf { it.isNotBlank() }
                        ?.let { header("Authorization", "Bearer $it") }
                }
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val result = runCatching {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> 0 to null
                        // 4xx: the batch itself is wrong. Drop it rather than
                        // retrying forever and blocking every later event.
                        response.code in 400..499 -> 0 to null
                        else -> 0 to "Server returned ${response.code}"
                    }
                }
            }.getOrElse { throwable ->
                0 to throwable.javaClass.simpleName
            }

            val failure = result.second
            if (failure == null) {
                // Accepted (or permanently rejected): those lines are done.
                file.writeText(rest.joinToString("\n", postfix = if (rest.isEmpty()) "" else "\n"))
                settings.setNotionLastSyncAt(System.currentTimeMillis())
                settings.setNotionSyncedTotal(settings.snapshot.notionSyncedTotal + events.size)
                settings.setNotionLastError(null)
                events.size to null
            } else {
                settings.setNotionLastError(failure)
                0 to failure
            }
        }

    private companion object {
        const val QUEUE_FILE = "pending.log"
        const val MAX_PER_FLUSH = 100
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
