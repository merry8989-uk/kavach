package com.kavach.caller.data.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Six levels, matching the taxonomy.
 *
 * `TRACE` is step-by-step detail that only makes sense when something is wrong,
 * and `FATAL` is a failure that ends the process. Their absence is why a crash
 * and a caught exception looked the same in the log.
 */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

/**
 * One structured log event.
 *
 * The envelope fields live on the event; the event-specific properties sit in
 * [properties] and are validated against [EventSchema] before the line is
 * written. There is no free-text field by design — that is what made the old
 * log unreadable.
 */
data class LogEvent(
    val eventName: String,
    val logLevel: LogLevel,
    val timestampMs: Long,
    val sessionId: String,
    val screenOrRoute: String,
    val attemptId: String? = null,
    /**
     * One id per user action, shared by everything that action causes.
     *
     * Without it, "the lookup failed" and "the screen that asked for it" are two
     * unrelated rows, and the only way to join them is by timestamp — which is a
     * guess. With it, one request can be followed across the app and, when a
     * server is configured, across the network too.
     */
    val requestId: String? = null,
    val userId: String? = null,
    val properties: Map<String, String> = emptyMap(),
) {
    /** ISO 8601, UTC — as the standard requires. */
    fun iso8601(): String = ISO_FORMAT.get().format(Date(timestampMs))

    private companion object {
        // SimpleDateFormat is not thread-safe, so each thread gets its own.
        //
        // Written as a plain ThreadLocal rather than withInitial, which only
        // exists from API 26 — and this app runs from API 24.
        val ISO_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}

/**
 * The non-event part of the envelope: who, where, which build.
 *
 * A plain value (no Android types) so the codec can be exercised in a JVM test
 * and so the same fields could be produced by a web client.
 */
data class EnvelopeFields(
    val platform: String = "android",
    val appVersion: String,
    val os: String,
    val deviceModel: String,
    val releaseChannel: String = "production",
    /** Readable per-install pseudonym, e.g. KV-4F9-K2Q. Not personal data. */
    val userCode: String = "KV-000-000",
    /**
     * Where this build is running. A developer's own noise and a user's real
     * session are different things, and mixing them makes every count wrong.
     */
    val environment: String = "production",
)

/** Reads and writes events as one JSON object per line. */
object LogEventCodec {

    private val JSON_ESCAPES = mapOf(
        '\\' to "\\\\", '"' to "\\\"", '\n' to "\\n", '\r' to "\\r", '\t' to "\\t",
    )

    fun encode(event: LogEvent, envelope: EnvelopeFields, networkType: String?): String {
        val out = LinkedHashMap<String, String>()
        out["event_name"] = event.eventName
        out["schema_version"] = EventSchema.VERSION
        out["timestamp"] = event.iso8601()
        out["session_id"] = event.sessionId
        event.attemptId?.let { out["attempt_id"] = it }
        event.userId?.let { out["user_id"] = it }
        out["platform"] = envelope.platform
        out["app_version"] = envelope.appVersion
        out["os"] = envelope.os
        out["device_model"] = envelope.deviceModel
        out["user_code"] = envelope.userCode
        networkType?.let { out["network_type"] = it }
        out["screen_or_route"] = event.screenOrRoute
        out["log_level"] = event.logLevel.name.lowercase(Locale.US)
        out["release_channel"] = envelope.releaseChannel
        out["environment"] = envelope.environment
        event.requestId?.let { out["request_id"] = it }
        // The catalogue knows what kind of log this is; carrying it on the line
        // means a reader can filter by category without the catalogue.
        EventSchema.specFor(event.eventName)?.let { out["category"] = it.category }

        val body = StringBuilder("{")
        var first = true
        out.forEach { (key, value) ->
            if (!first) body.append(',')
            first = false
            body.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
        }
        // Event-specific properties are flattened onto the same object so the
        // whole thing is one flat, queryable record.
        event.properties.forEach { (key, value) ->
            body.append(',')
            body.append('"').append(escape(key)).append("\":\"").append(escape(value)).append('"')
        }
        body.append('}')
        return body.toString()
    }

    fun decode(line: String): LogEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val map = parseFlatObject(trimmed) ?: return null
        val name = map["event_name"] ?: return null
        val level = runCatching {
            LogLevel.valueOf((map["log_level"] ?: "info").uppercase(Locale.US))
        }.getOrDefault(LogLevel.INFO)

        val reserved = setOf(
            "event_name", "schema_version", "timestamp", "session_id", "attempt_id",
            "user_id", "user_code", "platform", "app_version", "os", "device_model",
            "category", "request_id", "environment",
            "network_type", "screen_or_route", "log_level", "release_channel",
        )
        return LogEvent(
            eventName = name,
            logLevel = level,
            timestampMs = parseIso(map["timestamp"]) ?: System.currentTimeMillis(),
            sessionId = map["session_id"].orEmpty(),
            screenOrRoute = map["screen_or_route"].orEmpty(),
            attemptId = map["attempt_id"],
            userId = map["user_id"],
            properties = map.filterKeys { it !in reserved },
        )
    }

    private fun escape(value: String): String = buildString {
        value.forEach { ch -> append(JSON_ESCAPES[ch] ?: ch.toString()) }
    }

    /** Deliberately tiny: our lines are one flat object of strings. */
    private fun parseFlatObject(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        var i = 1
        val end = text.length - 1
        while (i < end) {
            if (text[i] == ',') { i++; continue }
            if (text[i] != '"') return null
            val key = StringBuilder()
            i++
            while (i < end && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < end) { key.append(text[i + 1]); i += 2; continue }
                key.append(text[i]); i++
            }
            i++ // closing quote
            if (i >= end || text[i] != ':') return null
            i++
            if (i >= end || text[i] != '"') return null
            i++
            val value = StringBuilder()
            while (i < end && text[i] != '"') {
                if (text[i] == '\\' && i + 1 < end) {
                    value.append(
                        when (text[i + 1]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> text[i + 1]
                        },
                    )
                    i += 2
                    continue
                }
                value.append(text[i]); i++
            }
            i++ // closing quote
            out[key.toString()] = value.toString()
        }
        return out
    }

    private fun parseIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(text)?.time
        }.getOrNull()
    }
}
