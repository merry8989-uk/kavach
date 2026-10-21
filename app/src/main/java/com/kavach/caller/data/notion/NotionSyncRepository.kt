package com.kavach.caller.data.notion

import com.kavach.caller.data.diagnostics.LogEvent
import com.kavach.caller.data.diagnostics.LogEventCodec
import com.kavach.caller.data.diagnostics.LogLevel
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

    /**
     * How a new page is parented.
     *
     * Notion's 2025-09-03 API introduced data sources, but which parent shape a
     * given workspace accepts depends on how the database was created. Rather
     * than guess and fail silently, we try one, fall back to the other, and
     * remember what worked.
     */
    private enum class ParentMode(val wire: String) {
        DATA_SOURCE("data_source_id"),
        DATABASE("database_id"),
    }

    private val prefs by lazy {
        context.getSharedPreferences("kavach_notion", Context.MODE_PRIVATE)
    }

    private var parentMode: ParentMode
        get() = runCatching {
            ParentMode.valueOf(prefs.getString("parent_mode", null) ?: ParentMode.DATA_SOURCE.name)
        }.getOrDefault(ParentMode.DATA_SOURCE)
        set(value) {
            prefs.edit().putString("parent_mode", value.name).apply()
        }

    private fun otherMode(mode: ParentMode): ParentMode =
        if (mode == ParentMode.DATA_SOURCE) ParentMode.DATABASE else ParentMode.DATA_SOURCE

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
            var result = post(event, bearer, parentMode)

            // A 400/404 on the very first row is almost always the parent shape,
            // not the row. Try the other one once and remember what worked.
            if (looksLikeParentProblem(result)) {
                val alternate = otherMode(parentMode)
                val retry = post(event, bearer, alternate)
                if (retry is PostResult.Sent) {
                    parentMode = alternate
                    result = retry
                }
            }

            when (result) {
                PostResult.Sent -> sent++

                is PostResult.Rejected -> {
                    // Stop and say why. The old code dropped these silently,
                    // which is exactly why "no logs are arriving" had no
                    // explanation anywhere.
                    failure = "Notion ${result.status} (${result.code}): ${result.message}"
                    remaining.add(line)
                    break
                }

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

        /** The request itself is wrong. Carries what Notion actually said. */
        data class Rejected(val status: Int, val code: String, val message: String) : PostResult

        /** Worth trying again later: network, timeout, 5xx. */
        data class Retry(val reason: String) : PostResult
    }

    /** True when the problem is the parent shape rather than the row. */
    private fun looksLikeParentProblem(result: PostResult): Boolean = when (result) {
        is PostResult.Rejected -> result.status == 400 || result.status == 404
        else -> false
    }

    private fun post(event: LogEvent, bearer: String, mode: ParentMode): PostResult {
        val snapshot = settings.snapshot
        val body = NotionEventMapper.toPage(
            event = event,
            userCode = session.userCode,
            appVersion = session.appVersion,
            os = session.os,
            network = "unknown",
            parentType = mode.wire,
            parentId = if (mode == ParentMode.DATABASE) {
                NotionEventMapper.DATABASE_ID
            } else {
                NotionEventMapper.DATA_SOURCE_ID
            },
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
                if (response.isSuccessful) return@use PostResult.Sent
                if (response.code >= 500) {
                    return@use PostResult.Retry("Notion returned ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                // Notion replies {"code":"validation_error","message":"..."}.
                // Showing that is the difference between "it does not work" and
                // "it does not work because the database is not shared".
                val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?: "http_${response.code}"
                val message = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?: body.take(160).ifBlank { "no detail returned" }
                PostResult.Rejected(response.code, code, message.take(200))
            }
        }.getOrElse { throwable ->
            PostResult.Retry(throwable.javaClass.simpleName)
        }
    }

    /**
     * Send one synthetic event and report exactly what Notion said.
     *
     * This exists because the first version could not be debugged: a rejected
     * row was dropped without a word. Now the user can press one button and see
     * the real status, code and message.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val bearer = token()
        if (bearer.isNullOrBlank()) return@withContext "No Notion token saved yet."

        val probe = LogEvent(
            eventName = "session_start",
            logLevel = LogLevel.INFO,
            timestampMs = System.currentTimeMillis(),
            sessionId = session.sessionId,
            screenOrRoute = "settings",
            userId = null,
            properties = mapOf("start_type" to "cold"),
        )

        var mode = parentMode
        var result = post(probe, bearer, mode)
        if (looksLikeParentProblem(result)) {
            mode = otherMode(mode)
            result = post(probe, bearer, mode)
        }

        when (result) {
            PostResult.Sent -> {
                parentMode = mode
                "Connected. Notion accepted a test row using '${mode.wire}'. " +
                    "Your database is shared with the integration and the schema matches."
            }
            is PostResult.Rejected -> {
                val hint = when (result.status) {
                    401 -> "The token is not valid — copy it again from the integration."
                    403 -> "The integration lacks permission. Enable 'Insert content' on it."
                    404 -> "The database was not found. Share the Kavach Diagnostics page " +
                        "with the integration (⋯ → Connections)."
                    400 -> "Notion rejected the row shape. Send this message on and it can " +
                        "be mapped: ${result.message}"
                    else -> result.message
                }
                "Notion said ${result.status} (${result.code}): ${result.message}\n\n$hint"
            }
            is PostResult.Retry -> "Could not reach Notion: ${result.reason}"
        }
    }

    /**
     * Queue everything already logged.
     *
     * Called when a destination is switched on, so the user sees it actually do
     * something instead of waiting for the next event to be written.
     */
    suspend fun backfillFromLog(lines: List<String>): Int {
        if (lines.isEmpty()) return 0
        val existing = runCatching { queueFile().takeIf { it.exists() }?.readLines() }.getOrNull()
            .orEmpty()
            .toHashSet()
        val fresh = lines.filterNot { it in existing }
        if (fresh.isEmpty()) return 0
        withContext(Dispatchers.IO) {
            runCatching {
                val file = queueFile()
                file.appendText(fresh.joinToString("\n", postfix = "\n"))
            }
        }
        return fresh.size
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
