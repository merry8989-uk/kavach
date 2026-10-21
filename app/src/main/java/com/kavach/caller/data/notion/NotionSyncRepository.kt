package com.kavach.caller.data.notion

import com.kavach.caller.data.diagnostics.LogEvent
import com.kavach.caller.data.diagnostics.LogEventCodec
import android.content.Context
import com.kavach.caller.data.diagnostics.SessionContext
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes diagnostics events into the Notion database
 * "Kavach Diagnostics — Events", so the dashboard fills itself.
 *
 * Design notes that matter:
 *
 *  - **The token is the user's own.** Kavach ships no Notion credentials. The
 *    user creates an internal integration, shares the two databases with it, and
 *    pastes the token into Settings. It is stored Keystore-encrypted and never
 *    leaves the device except to api.notion.com.
 *  - **Nothing is sent until the user turns this on.** The consent gate is the
 *    toggle, and it defaults to off.
 *  - **A queue, not a fire-and-forget.** Events are appended to a local queue
 *    first, so a flight, a dead zone or a Notion outage loses nothing.
 *  - **Notion's API creates one page per request** and rate-limits, so a flush
 *    is capped per run rather than hammering it.
 */
@Singleton
class NotionSyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val session: SessionContext,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Status(
        val enabled: Boolean,
        val hasToken: Boolean,
        val wifiOnly: Boolean,
        val pending: Int,
        val lastSyncAt: Long?,
        val lastError: String?,
        val syncedTotal: Long,
    )

    private fun token(): String? = secrets.get(SecretStore.NOTION_TOKEN)

    fun isConfigured(): Boolean =
        settings.snapshot.notionSyncEnabled && !token().isNullOrBlank()

    // ---------------- queue ----------------

    /** Called for every event once sync is on. */
    suspend fun enqueue(line: String) = withContext(Dispatchers.IO) {
        runCatching {
            val file = queueFile()
            file.appendText(line + "\n")
            // Keep the queue bounded; the log itself is the long-term record.
            if (file.length() > MAX_QUEUE_BYTES) {
                val kept = file.readLines().takeLast(MAX_QUEUE_LINES)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun queueFile(): File {
        val dir = File(context.filesDir, "notion-queue").apply { mkdirs() }
        return File(dir, QUEUE_FILE)
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        runCatching { queueFile().takeIf { it.exists() }?.readLines()?.size ?: 0 }.getOrDefault(0)
    }

    // ---------------- flush ----------------

    /**
     * Send queued events. Returns how many went and why it stopped, if it did.
     */
    suspend fun flush(limit: Int = MAX_PER_FLUSH): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val file = queueFile()
        if (!file.exists()) return@withContext 0 to null

        val bearer = token()
        if (bearer.isNullOrBlank()) return@withContext 0 to "No Notion token set."
        if (!settings.snapshot.notionSyncEnabled) return@withContext 0 to null

        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return@withContext 0 to null

        val batch = lines.take(limit)
        val remaining = lines.drop(batch.size).toMutableList()
        var sent = 0
        var failure: String? = null

        for (line in batch) {
            val event = LogEventCodec.decode(line)
            if (event == null) {
                // Not a schema event (legacy or corrupt) — drop it rather than
                // retrying it forever.
                continue
            }
            when (val result = post(event, bearer)) {
                PostResult.Sent -> sent++
                PostResult.Rejected -> Unit // bad row: drop, do not block the queue
                is PostResult.Retry -> {
                    failure = result.reason
                    remaining.add(line)
                    break
                }
            }
        }

        file.writeText(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n"))
        if (sent > 0) {
            settings.setNotionLastSyncAt(System.currentTimeMillis())
            settings.setNotionSyncedTotal(settings.snapshot.notionSyncedTotal + sent)
            settings.setNotionLastError(null)
        }
        failure?.let { settings.setNotionLastError(it) }
        sent to failure
    }

    private sealed interface PostResult {
        data object Sent : PostResult
        data object Rejected : PostResult
        data class Retry(val reason: String) : PostResult
    }

    private fun post(event: LogEvent, bearer: String): PostResult {
        val snapshot = settings.snapshot
        val body = NotionEventMapper.toPage(
            event = event,
            userCode = session.userCode,
            appVersion = session.appVersion,
            os = session.os,
            network = "unknown",
        )
        val payload = NotionEventMapper.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            body,
        )

        val request = Request.Builder()
            .url("https://api.notion.com/v1/pages")
            .header("Authorization", "Bearer $bearer")
            .header("Notion-Version", NotionEventMapper.NOTION_VERSION)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> PostResult.Sent
                    // 4xx means the row itself is wrong; retrying will not help.
                    response.code in 400..499 -> PostResult.Rejected
                    else -> PostResult.Retry("Notion returned ${response.code}")
                }
            }
        }.getOrElse { throwable ->
            PostResult.Retry(throwable.javaClass.simpleName)
        }
    }

    suspend fun clearQueue() = withContext(Dispatchers.IO) {
        runCatching { queueFile().writeText("") }
    }

    suspend fun status(): Status = Status(
        enabled = settings.snapshot.notionSyncEnabled,
        hasToken = !token().isNullOrBlank(),
        wifiOnly = settings.snapshot.notionWifiOnly,
        pending = pendingCount(),
        lastSyncAt = settings.snapshot.notionLastSyncAt.takeIf { it > 0 },
        lastError = settings.snapshot.notionLastError,
        syncedTotal = settings.snapshot.notionSyncedTotal,
    )

    private companion object {
        const val QUEUE_FILE = "pending.log"
        const val MAX_QUEUE_BYTES = 2L * 1024 * 1024
        const val MAX_QUEUE_LINES = 8_000
        const val MAX_PER_FLUSH = 40
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
