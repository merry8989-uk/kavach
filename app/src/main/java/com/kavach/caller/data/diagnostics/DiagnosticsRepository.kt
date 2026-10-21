package com.kavach.caller.data.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.kavach.caller.BuildConfig
import com.kavach.caller.data.notion.NotionSyncRepository
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The diagnostics log, rebuilt to the shared logging standard.
 *
 * What changed and why — the standard's own words: *"a log must capture intent,
 * outcome and context together, not intent alone."* The previous version wrote
 * free-text lines, which is exactly the anti-pattern the checklist forbids
 * ("structured format — never free-text strings") and is why a 71-attempt loop
 * was visible but unexplainable.
 *
 * Now every line is a JSON object validated against [EventSchema], carrying the
 * envelope (session, device, version, level) plus event-specific properties.
 * Nothing is written unless it names both what was attempted and how it ended.
 *
 * Privacy (Part F) is enforced in [Redaction], not trusted to call sites.
 * Retention (Part F) is enforced here, per class.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val session: SessionContext,
    private val notionSync: dagger.Lazy<NotionSyncRepository>,
) {
    private val lock = Any()
    private val directory = File(context.filesDir, DIRECTORY)
    private val file = File(directory, FILE_NAME)

    val isEnabled: Boolean get() = settings.snapshot.diagnosticsEnabled

    private val userId: String? by lazy {
        // Pseudonymous: a salted hash of the account id, never the id itself.
        Redaction.hashUser(settings.snapshot.accountUserId.ifBlank { null }, session.userSalt)
    }

    // ---------------- the one entry point ----------------

    /**
     * Write one event.
     *
     * Invalid events are dropped and a warning is raised about the drop, so a
     * schema mistake shows up as a gap in the log rather than as bad data.
     */
    suspend fun emit(
        eventName: String,
        level: LogLevel = LogLevel.INFO,
        properties: Map<String, String> = emptyMap(),
        attemptId: String? = null,
        screen: String? = null,
    ) {
        if (!isEnabled) return

        val report = EventValidator.validate(eventName, properties)
        if (!report.valid) {
            writeRaw(
                "warn",
                "schema_violation",
                mapOf("rejected_event" to eventName, "problem" to report.summary),
                null,
            )
            return
        }

        if (!shouldSample(eventName, level)) return

        writeRaw(level.name.lowercase(Locale.US), eventName, properties, attemptId, screen)
    }

    private suspend fun writeRaw(
        level: String,
        eventName: String,
        properties: Map<String, String>,
        attemptId: String?,
        screen: String? = null,
    ) {
        val clean = Redaction.scrubAll(properties)
        val event = LogEvent(
            eventName = eventName,
            logLevel = runCatching { LogLevel.valueOf(level.uppercase(Locale.US)) }
                .getOrDefault(LogLevel.INFO),
            timestampMs = System.currentTimeMillis(),
            sessionId = session.sessionId,
            screenOrRoute = screen ?: session.currentScreen,
            attemptId = attemptId,
            userId = userId,
            properties = clean,
        )
        val line = LogEventCodec.encode(event, session.envelope, networkType())

        // Mirror into the Notion queue *before* the file write, so an event that
        // reaches the dashboard is never lost to a rotation.
        if (settings.snapshot.notionSyncEnabled) {
            runCatching { notionSync.get().enqueue(line) }
        }

        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    directory.mkdirs()
                    file.appendText(line + "\n")
                    enforceRetention()
                    rotateIfNeeded()
                }
            }
        }
    }

    // ---------------- sampling (Part A) ----------------

    /**
     * 100% of warnings and errors, sampled info, debug only in debug builds.
     * A crash you never recorded is the one you cannot fix.
     */
    private fun shouldSample(eventName: String, level: LogLevel): Boolean = when (level) {
        LogLevel.ERROR, LogLevel.WARN -> true
        LogLevel.INFO -> (System.nanoTime() % INFO_SAMPLE_RATE) == 0L ||
            eventName in ALWAYS_KEEP
        LogLevel.DEBUG -> BuildConfig.DEBUG
    }

    // ---------------- retention (Part F) ----------------

    /**
     * How long each class is kept. Enforced on every write, so the file cannot
     * quietly grow past its policy.
     */
    private fun retentionDays(level: LogLevel): Int = when (level) {
        LogLevel.ERROR -> 30
        LogLevel.WARN -> 14
        LogLevel.INFO -> 7
        LogLevel.DEBUG -> 1
    }

    private fun enforceRetention() {
        if (!file.exists()) return
        val cutoff = System.currentTimeMillis() - MAX_RETENTION_DAYS * DAY_MS
        val lines = file.readLines()
        var changed = false
        val kept = lines.filter { line ->
            val event = LogEventCodec.decode(line)
            if (event == null) {
                // Unparseable legacy line: drop it, it predates the schema.
                changed = true
                return@filter false
            }
            val age = System.currentTimeMillis() - event.timestampMs
            val allowed = retentionDays(event.logLevel) * DAY_MS
            val keep = age <= allowed && event.timestampMs >= cutoff
            if (!keep) changed = true
            keep
        }
        if (changed) file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    // ---------------- reading ----------------

    suspend fun entries(limit: Int = 400): List<LogEvent> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching emptyList()
            file.readLines().asReversed().take(limit).mapNotNull { LogEventCodec.decode(it) }
        }.getOrDefault(emptyList())
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching { file.writeText("") } }
    }

    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    fun logFile(): File? = file.takeIf { it.exists() && it.length() > 0 }

    fun formattedSize(): String {
        val bytes = sizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    /** How many events of each level are on disk. Drives the UI summary. */
    suspend fun countsByLevel(): Map<LogLevel, Int> = withContext(Dispatchers.IO) {
        runCatching {
            file.readLines()
                .mapNotNull { LogEventCodec.decode(it)?.logLevel }
                .groupingBy { it }
                .eachCount()
        }.getOrDefault(emptyMap())
    }

    private fun rotateIfNeeded() {
        if (file.length() <= MAX_BYTES) return
        val kept = file.readLines().takeLast(MAX_LINES_KEPT)
        file.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    private fun networkType(): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }.getOrDefault("unknown")

    // ---------------- typed helpers (Part C events) ----------------
    // Named methods so call sites cannot invent property names.

    suspend fun sessionStart() = emit(
        "session_start",
        properties = mapOf("start_type" to "cold"),
    )

    /**
     * The real startup cost: process start to the first screen appearing.
     *
     * Reported once per session as a `performance_marker`, because the old
     * `cold_start_ms` property on `session_start` was declared and never
     * measured — which a real export showed as a permanently empty field.
     */
    suspend fun appStartIfFirstScreen() {
        if (session.appStartReported) return
        session.appStartReported = true
        val elapsed = SystemClock.elapsedRealtime() - session.processStartElapsedMs
        emit(
            "performance_marker",
            properties = mapOf("flow" to "app_start", "duration_ms" to elapsed.toString()),
        )
    }

    /**
     * Emit `feature_toggle` **only when a module's state actually changed**.
     *
     * A real export had 9 of 14 rows as feature toggles, repeated identically on
     * every launch, and — worse — sampled, so each session reported a different,
     * incomplete subset of the configuration. A configuration snapshot that is
     * partly missing is worse than none, because it reads as fact.
     *
     * @param current map of feature name to "enabled|STATUS"
     */
    suspend fun featureSnapshot(current: Map<String, String>) = withContext(Dispatchers.IO) {
        val previous = readFeatureSnapshot()
        current.forEach { (feature, state) ->
            if (previous[feature] == state) return@forEach
            val parts = state.split('|', limit = 2)
            emit(
                "feature_toggle",
                properties = mapOf(
                    "feature" to feature,
                    "enabled" to (parts.getOrNull(0) ?: "false"),
                    "status" to (parts.getOrNull(1) ?: "UNKNOWN"),
                ),
            )
        }
        runCatching {
            directory.mkdirs()
            featureStateFile.writeText(
                current.entries.joinToString("\n") { "${it.key}=${it.value}" },
            )
        }
    }

    private fun readFeatureSnapshot(): Map<String, String> = runCatching {
        if (!featureStateFile.exists()) return@runCatching emptyMap()
        featureStateFile.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private val featureStateFile: File get() = File(directory, "feature-state.txt")

    suspend fun screenView(name: String, previous: String?, durationMs: Long? = null) = emit(
        "screen_view",
        properties = buildMap {
            put("screen_name", name)
            previous?.let { put("previous_screen", it) }
            durationMs?.let { put("duration_ms", it.toString()) }
        },
    )

    suspend fun permissionStateResolved(permission: String, state: String, itemCount: Int? = null) =
        emit(
            "permission_state_resolved",
            properties = buildMap {
                put("permission", permission)
                put("state", state)
                itemCount?.let { put("item_count", it.toString()) }
            },
        )

    suspend fun accessFixRequested(
        permission: String,
        stateBefore: String,
        attemptId: String,
    ) = emit(
        "access_fix_requested",
        properties = mapOf("permission" to permission, "state_before" to stateBefore),
        attemptId = attemptId,
    )

    suspend fun accessFixResult(
        attemptId: String,
        actionTaken: String,
        stateAfter: String,
        outcome: String,
        errorCode: String? = null,
        durationMs: Long? = null,
    ) = emit(
        "access_fix_result",
        properties = buildMap {
            put("action_taken", actionTaken)
            put("state_after", stateAfter)
            put("outcome", outcome)
            errorCode?.let { put("error_code", it) }
            durationMs?.let { put("duration_ms", it.toString()) }
        },
        attemptId = attemptId,
    )

    suspend fun permissionLoop(permission: String, requestCount: Int) = emit(
        "permission_loop",
        level = LogLevel.WARN,
        properties = mapOf("permission" to permission, "request_count" to requestCount.toString()),
    )

    suspend fun dataQueryResult(
        source: String,
        itemCount: Int,
        queryMs: Long? = null,
        errorCode: String? = null,
    ) = emit(
        "data_query_result",
        properties = buildMap {
            put("source", source)
            put("item_count", itemCount.toString())
            queryMs?.let { put("query_ms", it.toString()) }
            errorCode?.let { put("error_code", it) }
        },
    )

    suspend fun apiRequest(
        endpoint: String,
        method: String,
        statusCode: Int,
        latencyMs: Long,
        retryCount: Int = 0,
    ) = emit(
        "api_request",
        level = if (statusCode >= 400) LogLevel.WARN else LogLevel.INFO,
        properties = mapOf(
            "endpoint" to endpoint,
            "method" to method,
            "status_code" to statusCode.toString(),
            "latency_ms" to latencyMs.toString(),
            "retry_count" to retryCount.toString(),
        ),
    )

    suspend fun callScreened(
        action: String,
        outcome: String,
        hasRule: Boolean,
        numberType: String? = null,
    ) = emit(
        "call_screened",
        properties = buildMap {
            put("action", action)
            put("outcome", outcome)
            put("has_rule", hasRule.toString())
            numberType?.let { put("number_type", it) }
        },
    )

    suspend fun callState(direction: String, phase: String, hasSim: Boolean, hasName: Boolean) = emit(
        "call_state",
        properties = mapOf(
            "direction" to direction,
            "phase" to phase,
            "has_sim" to hasSim.toString(),
            "has_name" to hasName.toString(),
        ),
    )

    suspend fun smsSent(outcome: String, parts: Int? = null, errorCode: String? = null) = emit(
        "sms_sent",
        level = if (outcome == "failed") LogLevel.WARN else LogLevel.INFO,
        properties = buildMap {
            put("outcome", outcome)
            parts?.let { put("parts", it.toString()) }
            errorCode?.let { put("error_code", it) }
        },
    )

    suspend fun autoReply(outcome: String, errorCode: String? = null) = emit(
        "auto_reply",
        properties = buildMap {
            put("outcome", outcome)
            errorCode?.let { put("error_code", it) }
        },
    )

    suspend fun updateCheck(
        outcome: String,
        currentVersion: String,
        availableVersion: String? = null,
        errorCode: String? = null,
    ) = emit(
        "update_check",
        properties = buildMap {
            put("outcome", outcome)
            put("current_version", currentVersion)
            availableVersion?.let { put("available_version", it) }
            errorCode?.let { put("error_code", it) }
        },
    )

    suspend fun featureToggle(feature: String, enabled: Boolean, status: String? = null) = emit(
        "feature_toggle",
        properties = buildMap {
            put("feature", feature)
            put("enabled", enabled.toString())
            status?.let { put("status", it) }
        },
    )

    suspend fun identityLookup(
        outcome: String,
        source: String? = null,
        hasName: Boolean = false,
        confidenceBand: String? = null,
    ) = emit(
        "identity_lookup",
        properties = buildMap {
            put("outcome", outcome)
            source?.let { put("source", it) }
            put("has_name", hasName.toString())
            confidenceBand?.let { put("confidence_band", it) }
        },
    )

    suspend fun errorStateShown(errorCode: String, screen: String, messageKey: String? = null) =
        emit(
            "error_state_shown",
            level = LogLevel.WARN,
            properties = buildMap {
                put("error_code", errorCode)
                put("screen", screen)
                messageKey?.let { put("message_key", it) }
            },
        )

    // ---- user-friendliness signals (Part E) ----

    suspend fun rageTap(element: String, tapCount: Int) = emit(
        "rage_tap",
        level = LogLevel.WARN,
        properties = mapOf(
            "element" to element,
            "tap_count" to tapCount.toString(),
            "window_ms" to RAGE_WINDOW_MS.toString(),
        ),
    )

    suspend fun deadClick(element: String, screen: String? = null) = emit(
        "dead_click",
        level = LogLevel.WARN,
        properties = buildMap {
            put("element", element)
            screen?.let { put("screen", it) }
        },
    )

    suspend fun backButtonSpam(pressCount: Int) = emit(
        "back_button_spam",
        level = LogLevel.WARN,
        properties = mapOf(
            "press_count" to pressCount.toString(),
            "window_ms" to RAGE_WINDOW_MS.toString(),
        ),
    )

    suspend fun dropOff(step: String, lastEvent: String? = null) = emit(
        "drop_off",
        properties = buildMap {
            put("step", step)
            lastEvent?.let { put("last_event", it) }
        },
    )

    suspend fun timeToFirstValue(durationMs: Long, firstAction: String? = null) = emit(
        "time_to_first_value",
        properties = buildMap {
            put("duration_ms", durationMs.toString())
            firstAction?.let { put("first_action", it) }
        },
    )

    suspend fun crash(exceptionType: String, stackTrace: String?, lastAction: String? = null) =
        emit(
            "crash",
            level = LogLevel.ERROR,
            properties = buildMap {
                put("fatal", "true")
                put("exception_type", exceptionType)
                put("stack_trace_hash", Redaction.hashStack(stackTrace))
                lastAction?.let { put("last_action", it) }
            },
        )

    // ---------------- export ----------------

    suspend fun export(format: ExportFormat): File? = withContext(Dispatchers.IO) {
        val events = entries(limit = MAX_EXPORT_ENTRIES)
        if (events.isEmpty()) return@withContext null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val target = File(exportDir, "kavach-diagnostics-$stamp.${format.extension}")

        runCatching {
            when (format) {
                // The log file *is* the schema, so TXT/JSON are the same bytes.
                ExportFormat.TXT, ExportFormat.JSON -> target.writeText(rawLines(events))
                ExportFormat.CSV -> target.writeText(asCsv(events))
                ExportFormat.MARKDOWN -> target.writeText(asMarkdown(events))
                ExportFormat.ZIP -> writeZip(target, events, stamp)
            }
        }.getOrNull()

        target.takeIf { it.exists() && it.length() > 0 }
    }

    private fun rawLines(events: List<LogEvent>): String =
        events.asReversed().joinToString("\n") { event ->
            LogEventCodec.encode(event, session.envelope, null)
        }

    private fun asCsv(events: List<LogEvent>): String = buildString {
        appendLine(
            "timestamp,event_name,log_level,session_id,attempt_id,screen,properties",
        )
        events.asReversed().forEach { event ->
            append(csv(event.iso8601())).append(',')
            append(csv(event.eventName)).append(',')
            append(csv(event.logLevel.name.lowercase(Locale.US))).append(',')
            append(csv(event.sessionId)).append(',')
            append(csv(event.attemptId.orEmpty())).append(',')
            append(csv(event.screenOrRoute)).append(',')
            appendLine(csv(event.properties.entries.joinToString(" ") { "${it.key}=${it.value}" }))
        }
    }

    private fun asMarkdown(events: List<LogEvent>): String = buildString {
        appendLine("# Kavach diagnostics report")
        appendLine()
        appendLine("- **Schema version:** ${EventSchema.VERSION}")
        appendLine("- **App:** ${session.appVersion}")
        appendLine("- **OS:** ${session.os}")
        appendLine("- **Device:** ${session.deviceModel}")
        appendLine("- **User code:** ${session.userCode}")
        appendLine("- **Session:** ${session.sessionId}")
        appendLine("- **Events:** ${events.size}")
        appendLine()
        appendLine(
            "Every line is a schema-validated JSON event. Phone numbers, names, " +
                "message contents, file paths and queries are never recorded.",
        )
        appendLine()
        appendLine("| Time (UTC) | Event | Level | Properties |")
        appendLine("|---|---|---|---|")
        events.asReversed().forEach { event ->
            val props = event.properties.entries.joinToString(" ") { "${it.key}=${it.value}" }
            appendLine(
                "| ${event.iso8601()} | ${event.eventName} | " +
                    "${event.logLevel.name.lowercase(Locale.US)} | $props |",
            )
        }
    }

    private fun writeZip(target: File, events: List<LogEvent>, stamp: String) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("kavach-diagnostics-$stamp.json", rawLines(events))
            put("kavach-diagnostics-$stamp.csv", asCsv(events))
            put("kavach-diagnostics-$stamp.md", asMarkdown(events))
            put("event-schema.txt", schemaSummary())
        }
    }

    private fun schemaSummary(): String = buildString {
        appendLine("Kavach event schema v${EventSchema.VERSION}")
        appendLine()
        EventSchema.CATALOGUE.values.sortedBy { it.name }.forEach { spec ->
            appendLine("${spec.name}")
            appendLine("  required: ${spec.required.joinToString(", ")}")
            if (spec.optional.isNotEmpty()) {
                appendLine("  optional: ${spec.optional.joinToString(", ")}")
            }
            spec.enums.forEach { (key, allowed) ->
                appendLine("  $key ∈ {${allowed.joinToString(", ")}}")
            }
        }
    }

    /** Body for the "email to developer" flow. */
    fun emailBody(entries: Int): String = buildString {
        appendLine("Kavach diagnostics")
        appendLine()
        appendLine("App version: ${session.appVersion}")
        appendLine("Android: ${session.os}")
        appendLine("Device: ${session.deviceModel}")
        appendLine("Schema version: ${EventSchema.VERSION}")
        appendLine("User code: ${session.userCode}")
        appendLine("Session: ${session.sessionId}")
        appendLine("Events: $entries")
        appendLine("Log size: ${formattedSize()}")
        appendLine()
        appendLine("Every line is a schema-validated JSON event, so intent and outcome")
        appendLine("are recorded together. Numbers are masked, and names, message")
        appendLine("contents, file paths and queries are never logged.")
        appendLine()
        appendLine("What went wrong:")
        appendLine()
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    companion object {
        private const val DIRECTORY = "diagnostics"
        private const val FILE_NAME = "kavach.log"
        private const val MAX_BYTES = 512L * 1024L
        private const val MAX_LINES_KEPT = 4_000
        private const val MAX_EXPORT_ENTRIES = 5_000
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val MAX_RETENTION_DAYS = 30L
        private const val INFO_SAMPLE_RATE = 4L
        private const val RAGE_WINDOW_MS = 2_000L

        /** Events worth every occurrence, however noisy they look. */
        /**
         * Events that must never be sampled away.
         *
         * `feature_toggle` is here because it is now a *change* record: sampling
         * it would silently lose the fact that a module was switched off, which
         * is exactly the context a bug report needs.
         */
        private val ALWAYS_KEEP = setOf(
            "session_start", "access_fix_result", "access_fix_requested",
            "permission_state_resolved", "error_state_shown", "crash",
            "feature_toggle", "performance_marker",
        )

        /** Kept for call sites that only need a masked number. */
        fun mask(raw: String?): String = Redaction.maskNumber(raw)
    }
}
