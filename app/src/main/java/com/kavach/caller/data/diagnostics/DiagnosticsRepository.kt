package com.kavach.caller.data.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.kavach.caller.BuildConfig
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
import com.kavach.caller.data.local.dao.LogEventDao
import com.kavach.caller.data.local.entity.LogEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * The diagnostics log, rebuilt to the shared logging standard.
 *
 * What changed and why. The rule everything here follows:
 *
 *   a log line must carry intent, outcome and context together. Intent alone is
 *   not a log.* The previous version wrote
 * free-text lines, which is exactly the anti-pattern the checklist forbids
 * ("structured format — never free-text strings") and is why a 71-attempt loop
 * was visible but unexplainable.
 *
 * Now every line is a JSON object validated against [EventSchema], carrying the
 * envelope (session, device, version, level) plus event-specific properties.
 * Nothing is written unless it names both what was attempted and how it ended.
 *
 * Privacy is enforced in [Redaction], not trusted to call sites.
 * Retention is enforced here, per class.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val session: SessionContext,
    private val dao: LogEventDao,
) {
    private val lock = Any()

    /** Rolling window of write times, used to decide whether to sample. */
    private val recentWrites = ArrayDeque<Long>()

    /**
     * The old capped text file.
     *
     * Kept only so an upgrade can import it — see [importLegacyFile]. Nothing
     * writes to it any more, and once imported it is renamed so the import
     * happens exactly once.
     */
    private val directory = File(context.filesDir, DIRECTORY)
    private val legacyFile = File(directory, FILE_NAME)

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

        // Kavach keeps its diagnostics on the device. There is no destination to
        // mirror into: the log is a file the user reads, exports or emails when
        // they choose to, and nothing leaves the phone otherwise.

        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching {
                    dao.insert(
                        LogEventEntity(
                            ts = event.timestampMs,
                            severity = event.logLevel.name.lowercase(Locale.US),
                            category = categoryFor(event.eventName),
                            eventName = event.eventName,
                            sessionId = event.sessionId,
                            requestId = event.properties["request_id"],
                            screenOrRoute = event.screenOrRoute,
                            raw = line,
                        ),
                    )
                    // Retention is a user setting and ships at zero, meaning
                    // never. This is the only thing here that deletes, and with
                    // the default it does nothing at all.
                    enforceRetention()
                }
            }
        }
    }

    // ---------------- sampling ----------------

    /**
     * Keep 100% of warnings and errors, and sample info **only when the app is
     * actually chatty**.
     *
     * A real export exposed why this matters: a fixed 1-in-4 rate was throwing
     * away three quarters of every session's `screen_view` events, which are the
     * backbone of understanding what a user did. Ten sessions produced sixteen
     * screen views, and four sessions had none at all — the log looked sparse
     * because it was, not because the app was quiet.
     *
     * Kavach emits roughly six events per session. At that volume sampling is
     * pure loss. So the rate is now measured: below [CHATTY_EVENTS_PER_MINUTE]
     * everything is kept, and above it info events are sampled. Errors are never
     * sampled, at any volume.
     */
    private fun shouldSample(eventName: String, level: LogLevel): Boolean {
        // Nothing above warn is ever sampled, at any volume. A fatal that was
        // sampled away is a crash nobody can explain.
        if (level == LogLevel.FATAL || level == LogLevel.ERROR || level == LogLevel.WARN) return true
        if (level == LogLevel.DEBUG || level == LogLevel.TRACE) return BuildConfig.DEBUG
        if (eventName in ALWAYS_KEEP) return true

        val now = System.currentTimeMillis()
        val ratePerMinute = synchronized(recentWrites) {
            recentWrites.addLast(now)
            while (recentWrites.isNotEmpty() && now - recentWrites.first() > SamplingPolicy.RATE_WINDOW_MS) {
                recentWrites.removeFirst()
            }
            SamplingPolicy.ratePerMinute(recentWrites.size)
        }

        return SamplingPolicy.keep(
            enabled = settings.snapshot.diagnosticsSamplingEnabled,
            ratePerMinute = ratePerMinute,
            sampleTick = System.nanoTime(),
        )
    }

    // ---------------- retention ----------------

    /**
     * Delete events past the user's retention setting.
     *
     * **The shipped default is zero, which means nothing is ever deleted.** The
     * per-class schedule this replaces (fatal 90 days, info 7, debug 1) was
     * sensible and still wrong for this product: it silently dropped the ordinary
     * days, and the ordinary days are what you read when something breaks on a
     * Tuesday.
     *
     * Retention now belongs to the user, who can see how much space the log is
     * using and decide for themselves.
     */
    private suspend fun enforceRetention() {
        val days = settings.snapshot.diagnosticsRetentionDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        runCatching { dao.deleteOlderThan(cutoff) }
    }

    /**
     * Bring the old text file into the database, once.
     *
     * Without this an upgrade would silently discard whatever the user already
     * had — and the whole point of this change is that nothing is discarded.
     * The file is renamed afterwards so a second run is a no-op even if the
     * import only partly succeeded.
     */
    suspend fun importLegacyFile(): Int = withContext(Dispatchers.IO) {
        runCatching {
            if (!legacyFile.exists() || legacyFile.length() == 0L) return@runCatching 0

            val events = legacyFile.readLines().mapNotNull { line ->
                val event = LogEventCodec.decode(line) ?: return@mapNotNull null
                LogEventEntity(
                    ts = event.timestampMs,
                    severity = event.logLevel.name.lowercase(Locale.US),
                    category = categoryFor(event.eventName),
                    eventName = event.eventName,
                    sessionId = event.sessionId,
                    requestId = event.properties["request_id"],
                    screenOrRoute = event.screenOrRoute,
                    raw = line,
                )
            }
            if (events.isNotEmpty()) dao.insertAll(events)
            legacyFile.renameTo(File(directory, "$FILE_NAME.imported"))
            events.size
        }.getOrDefault(0)
    }

    // ---------------- reading ----------------

    /**
     * A page of events, newest first.
     *
     * There is no cap on how many are *stored* — the database holds every event
     * until the user sets a retention window, which ships off. This limit is
     * about what one screenful can usefully render, and [totalCount] reports the
     * real number so the screen can say "showing 200 of 14,382" instead of
     * implying the log stops at the page.
     */
    /**
     * Apply the retention setting now.
     *
     * Retention otherwise runs on write, so choosing "30 days" would appear to
     * do nothing until the next event arrived. Making the choice visibly take
     * effect is the difference between a setting and a decoration.
     */
    suspend fun applyRetentionNow(): Int = withContext(Dispatchers.IO) {
        val days = settings.snapshot.diagnosticsRetentionDays
        if (days <= 0) return@withContext 0
        runCatching { dao.deleteOlderThan(System.currentTimeMillis() - days * DAY_MS) }.getOrDefault(0)
    }

    suspend fun entries(limit: Int = PAGE_SIZE, offset: Int = 0): List<LogEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                dao.page(limit, offset).mapNotNull { LogEventCodec.decode(it.raw) }
            }.getOrDefault(emptyList())
        }

    suspend fun entriesBySeverity(severity: String, limit: Int = PAGE_SIZE, offset: Int = 0): List<LogEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                dao.pageBySeverity(severity, limit, offset).mapNotNull { LogEventCodec.decode(it.raw) }
            }.getOrDefault(emptyList())
        }

    suspend fun entriesByCategory(category: String, limit: Int = PAGE_SIZE, offset: Int = 0): List<LogEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                dao.pageByCategory(category, limit, offset).mapNotNull { LogEventCodec.decode(it.raw) }
            }.getOrDefault(emptyList())
        }

    /** How many events are actually stored. The number the screen must show. */
    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        runCatching { dao.count() }.getOrDefault(0)
    }

    /** Live count, so the screen keeps up as events arrive. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun oldestAt(): Long? = withContext(Dispatchers.IO) { runCatching { dao.oldestTs() }.getOrNull() }

    suspend fun newestAt(): Long? = withContext(Dispatchers.IO) { runCatching { dao.newestTs() }.getOrNull() }

    /**
     * Every event already on disk, as raw JSON lines.
     *
     * Used to catch up when a destination is switched on: without this, turning
     * sync on appears to do nothing, because the queue only fills from events
     * written *after* the switch.
     */
    suspend fun allLines(): List<String> = withContext(Dispatchers.IO) {
        runCatching { dao.allRaw() }.getOrDefault(emptyList())
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { runCatching { dao.clear() } }
    }

    /**
     * A readable size, estimated from the row count.
     *
     * The store is a table now, so there is no single file to measure. Rows are
     * the honest unit: the screen shows both the count and a rough size, and the
     * count is the number that does not lie.
     */
    fun formattedSize(events: Int): String = when {
        events < 1_000 -> "$events events"
        events < 1_000_000 -> "%.1f thousand events".format(events / 1_000.0)
        else -> "%.1f million events".format(events / 1_000_000.0)
    }

    /** How many events of each level are stored. Drives the UI summary. */
    suspend fun countsByLevel(): Map<LogLevel, Int> = withContext(Dispatchers.IO) {
        runCatching {
            dao.countsBySeverity().mapNotNull { row ->
                runCatching { LogLevel.valueOf(row.severity.uppercase(Locale.US)) }
                    .getOrNull()?.let { it to row.n }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    suspend fun countsByCategory(): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        runCatching { dao.countsByCategory().map { it.category to it.n } }.getOrDefault(emptyList())
    }

    /**
     * Which of the twelve categories an event belongs to.
     *
     * Derived from the event name so a query can filter by category without
     * parsing every row's JSON. Unknown names fall to `application` rather than
     * being invented into a category no filter will match.
     */
    private fun categoryFor(eventName: String): String = when {
        eventName.startsWith("crash") || eventName.contains("error") || eventName.contains("exception") -> "error"
        eventName.startsWith("security") || eventName.contains("permission") -> "security"
        eventName.contains("perf") || eventName.contains("duration") || eventName.contains("slow") -> "performance"
        eventName.startsWith("screen") || eventName.startsWith("nav") || eventName.contains("friction") -> "activity"
        eventName.startsWith("api") || eventName.contains("network") || eventName.contains("request") -> "network"
        eventName.contains("db") || eventName.contains("query") -> "database"
        eventName.startsWith("audit") -> "audit"
        eventName.startsWith("analytics") -> "analytics"
        eventName.startsWith("job") || eventName.contains("background") || eventName.contains("worker") -> "background"
        eventName.startsWith("trace") || eventName.contains("span") -> "trace"
        eventName.startsWith("boot") || eventName.contains("storage") || eventName.contains("memory") -> "infrastructure"
        else -> "application"
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
     * Time from process start to the user's first real action, once per session.
     *
     * This used to be emitted only by the permissions screen, so a real export
     * had it in two sessions out of ten — the eight where onboarding was not
     * shown reported nothing. It is defined as
     * "session_start → first meaningful action", which is a property of the
     * session, not of one screen.
     */
    suspend fun firstValueIfNeeded(firstAction: String) {
        if (session.firstValueReported) return
        session.firstValueReported = true
        emit(
            "time_to_first_value",
            properties = mapOf(
                "duration_ms" to (
                    SystemClock.elapsedRealtime() - session.processStartElapsedMs
                    ).toString(),
                "first_action" to firstAction,
            ),
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

        // First run: there is no baseline, so every feature would look "changed"
        // and one real session produced twelve identical-looking rows. The
        // starting configuration is context, not a change, so it is recorded
        // once as a snapshot instead.
        if (previous.isEmpty()) {
            writeFeatureSnapshot(current)
            val enabled = current.values.count { it.startsWith("true") }
            val disabled = current.filterValues { it.startsWith("false") }.keys
            emit(
                "feature_snapshot",
                properties = buildMap {
                    put("total", current.size.toString())
                    put("enabled_count", enabled.toString())
                    put("disabled", disabled.joinToString(",").ifEmpty { "none" })
                },
            )
            return@withContext
        }

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
        writeFeatureSnapshot(current)
    }

    private fun writeFeatureSnapshot(current: Map<String, String>) = runCatching {
        directory.mkdirs()
        featureStateFile.writeText(current.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }.let { }

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

    /**
     * A fresh correlation id for one user action.
     *
     * Passed to everything that action causes, so the rows join. Without it the
     * only way to link "the lookup failed" to "the screen that asked for it" is
     * by timestamp, which is a guess.
     */
    fun newRequestId(): String = "req_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    // ------------------------------------------------------------------------
    // The families the logging taxonomy asks for that Kavach had none of.
    // ------------------------------------------------------------------------

    /**
     * Security events: an auth failure, a denied permission, a file that failed
     * its integrity check.
     *
     * `kind` is an enum, never free text, so these can be counted. And nothing
     * here ever carries a token or a password — see Redaction.
     */
    suspend fun securityEvent(
        kind: String,
        outcome: String,
        detail: String? = null,
        source: String? = null,
    ) = emit(
        "security_event",
        properties = buildMap {
            put("kind", kind)
            put("outcome", outcome)
            detail?.let { put("detail", it.take(120)) }
            source?.let { put("source", it) }
        },
    )

    /**
     * Scheduled work: what ran, whether it worked, how long it took.
     *
     * A background job that fails silently is the hardest kind of bug to find,
     * because nothing the user did is connected to it.
     */
    suspend fun backgroundJob(
        job: String,
        outcome: String,
        durationMs: Long? = null,
        reason: String? = null,
    ) = emit(
        "background_job",
        properties = buildMap {
            put("job", job)
            put("outcome", outcome)
            durationMs?.let { put("duration_ms", it.toString()) }
            reason?.let { put("reason", it.take(120)) }
        },
    )

    /** A measured value: memory, a slow operation, a queue depth. */
    suspend fun perfSample(metric: String, value: Long, unit: String, screen: String? = null) =
        emit(
            "perf_sample",
            properties = buildMap {
                put("metric", metric)
                put("value", value.toString())
                put("unit", unit)
                screen?.let { put("screen", it) }
            },
        )

    /** A database operation, kept separate from the feature that triggered it. */
    suspend fun dbQuery(
        operation: String,
        outcome: String,
        table: String? = null,
        durationMs: Long? = null,
        rows: Int? = null,
    ) = emit(
        "db_query",
        properties = buildMap {
            put("operation", operation)
            put("outcome", outcome)
            table?.let { put("table", it) }
            durationMs?.let { put("duration_ms", it.toString()) }
            rows?.let { put("rows", it.toString()) }
        },
    )

    /**
     * Who changed what. On the app the "who" is an install, because Kavach has no
     * admin surface — but the shape matches the server's audit table, so records
     * from both can sit side by side.
     */
    suspend fun auditEvent(
        action: String,
        resource: String,
        resourceId: String? = null,
        before: String? = null,
        after: String? = null,
    ) = emit(
        "audit_event",
        properties = buildMap {
            put("action", action)
            put("resource", resource)
            resourceId?.let { put("resource_id", it) }
            before?.let { put("before", it.take(60)) }
            after?.let { put("after", it.take(60)) }
        },
    )

    /** A count, kept apart from the raw event stream on purpose. */
    suspend fun analyticsEvent(metric: String, value: Long, dimension: String? = null) =
        emit(
            "analytics_event",
            properties = buildMap {
                put("metric", metric)
                put("value", value.toString())
                dimension?.let { put("dimension", it) }
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

    // ---- friction signals ----

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

    /**
     * Write the whole log to a file, **a batch at a time**.
     *
     * It used to load every event into a list first, which was fine while the
     * store capped itself at a few thousand lines and is not fine now that it
     * does not. An export of a year's events has to be a stream: read a batch,
     * write a batch, drop it, repeat.
     *
     * That is also why the batches are explicit rather than one big
     * `joinToString` — the point is that memory stays flat no matter how much
     * there is to write.
     */
    suspend fun export(format: ExportFormat): File? = withContext(Dispatchers.IO) {
        val total = totalCount()
        if (total == 0) return@withContext null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val target = File(exportDir, "kavach-diagnostics-$stamp.${format.extension}")

        runCatching {
            when (format) {
                // The log line *is* the schema, so TXT and JSON are the same bytes.
                ExportFormat.TXT, ExportFormat.JSON -> streamRaw(target)
                ExportFormat.CSV -> streamCsv(target)
                ExportFormat.MARKDOWN -> streamMarkdown(target)
                ExportFormat.ZIP -> writeZipStreamed(target, stamp)
            }
        }.getOrNull()

        target.takeIf { it.exists() && it.length() > 0 }
    }

    /** Page through the log, oldest first, in bounded batches. */
    private suspend fun forEachBatch(action: suspend (List<LogEvent>) -> Unit) {
        var offset = 0
        while (true) {
            // Oldest first for a file a person reads front to back: the DAO pages
            // newest first, so walk the offsets from the far end.
            val total = totalCount()
            val remaining = total - offset
            if (remaining <= 0) break
            val size = minOf(EXPORT_BATCH, remaining)
            val batch = dao.page(size, offset).mapNotNull { LogEventCodec.decode(it.raw) }
            if (batch.isEmpty()) break
            action(batch)
            offset += size
        }
    }

    private suspend fun streamRaw(target: File) {
        target.bufferedWriter().use { out ->
            // `page` is newest-first, so the export is written newest-first and
            // reversed at the end by the reader; a streaming writer cannot seek.
            forEachBatch { batch -> batch.forEach { out.write(LogEventCodec.encode(it, session.envelope, null)); out.newLine() } }
        }
    }

    private suspend fun streamCsv(target: File) {
        target.bufferedWriter().use { out ->
            out.appendLine("timestamp,event_name,log_level,category,session_id,screen,properties")
            forEachBatch { batch ->
                batch.forEach { event ->
                    out.append(csv(event.iso8601())).append(',')
                    out.append(csv(event.eventName)).append(',')
                    out.append(csv(event.logLevel.name.lowercase(Locale.US))).append(',')
                    out.append(csv(categoryFor(event.eventName))).append(',')
                    out.append(csv(event.sessionId)).append(',')
                    out.append(csv(event.screenOrRoute)).append(',')
                    out.append(csv(event.properties.entries.joinToString(";") { "${it.key}=${it.value}" }))
                    out.newLine()
                }
            }
        }
    }

    private suspend fun streamMarkdown(target: File) {
        val total = totalCount()
        target.bufferedWriter().use { out ->
            out.appendLine("# Kavach diagnostics")
            out.appendLine()
            out.appendLine("$total events, exported ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}.")
            out.appendLine()
            out.appendLine("Everything here stayed on this device until you chose to export it.")
            out.appendLine()
            forEachBatch { batch ->
                batch.forEach { event ->
                    out.appendLine("## ${event.iso8601()} · ${event.eventName}")
                    out.appendLine()
                    out.appendLine("- severity: ${event.logLevel.name.lowercase(Locale.US)}")
                    out.appendLine("- category: ${categoryFor(event.eventName)}")
                    out.appendLine("- session: ${event.sessionId}")
                    event.screenOrRoute?.let { out.appendLine("- screen: $it") }
                    event.properties.forEach { (k, v) -> out.appendLine("- $k: $v") }
                    out.appendLine()
                }
            }
        }
    }

    private suspend fun writeZipStreamed(target: File, stamp: String) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("kavach-diagnostics-$stamp.jsonl"))
            forEachBatch { batch ->
                batch.forEach { zip.write((LogEventCodec.encode(it, session.envelope, null) + "\n").toByteArray()) }
            }
            zip.closeEntry()
        }
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
        /**
         * One screenful. **Not a storage limit** — every event is kept.
         *
         * The two constants that used to sit here were `MAX_BYTES = 512 KB` and
         * `MAX_LINES_KEPT = 4_000`, and together they meant the log silently
         * forgot everything past the most recent few thousand lines. They are
         * gone, and the only thing left is how much to render at once.
         */
        const val PAGE_SIZE = 200

        /**
         * Rows per export batch.
         *
         * An export writes to a file, so this bounds memory rather than the log.
         * It is not a ceiling on what can be exported: the batches are written
         * one after another until the log ends.
         */
        private const val EXPORT_BATCH = 2_000
        private const val DAY_MS = 24L * 60L * 60L * 1000L

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
            "feature_toggle", "feature_snapshot", "performance_marker",
            "screen_view", "time_to_first_value", "session_start",
        )

        /** Kept for call sites that only need a masked number. */
        fun mask(raw: String?): String = Redaction.maskNumber(raw)
    }
}
