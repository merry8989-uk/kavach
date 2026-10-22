package com.kavach.caller

import com.kavach.caller.data.diagnostics.EnvelopeFields
import com.kavach.caller.data.diagnostics.EventSchema
import com.kavach.caller.data.diagnostics.EventValidator
import com.kavach.caller.data.diagnostics.LogEvent
import com.kavach.caller.data.diagnostics.LogEventCodec
import com.kavach.caller.data.diagnostics.LogLevel
import com.kavach.caller.data.diagnostics.Redaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logging standard's CI gate: "validate every event against this schema in
 * CI before it can ship", plus the privacy rules from Part F.
 *
 * These are pure JVM tests — no device needed — which is the point: a schema
 * mistake should fail the build, not a user's session.
 */
class LoggingSchemaTest {

    private val envelope = EnvelopeFields(
        platform = "android",
        appVersion = "0.18.0 (42)",
        os = "Android 14 (API 34)",
        deviceModel = "Google Pixel 7",
        releaseChannel = "production",
        userCode = "KV-4F9-K2Q",
    )

    private fun event(
        name: String,
        properties: Map<String, String> = emptyMap(),
        attemptId: String? = null,
        level: LogLevel = LogLevel.INFO,
    ) = LogEvent(
        eventName = name,
        logLevel = level,
        timestampMs = 1_760_000_000_000,
        sessionId = "sess_test",
        screenOrRoute = "PermissionsScreen",
        attemptId = attemptId,
        properties = properties,
    )

    // ---------------- catalogue shape ----------------

    @Test
    fun `catalogue satisfies the standards naming and completeness rules`() {
        val problems = EventValidator.validateCatalogue()
        assertTrue("catalogue problems: $problems", problems.isEmpty())
    }

    @Test
    fun `every event name follows object_action snake_case`() {
        // The standard's own catalogue has single-word events ("crash", "anr"),
        // so zero underscore groups is legal. camelCase is not.
        val pattern = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        EventSchema.CATALOGUE.keys.forEach { name ->
            assertTrue("$name is not snake_case", pattern.matches(name))
        }
        assertFalse("camelCase must be rejected", pattern.matches("FixAccessResult"))
    }

    @Test
    fun `the intent to outcome pair exists`() {
        // The whole reason the standard exists.
        assertTrue(EventSchema.isKnown("access_fix_requested"))
        assertTrue(EventSchema.isKnown("access_fix_result"))
        assertTrue(
            "access_fix_result must record an outcome",
            "outcome" in EventSchema.CATALOGUE.getValue("access_fix_result").required,
        )
    }

    // ---------------- validation ----------------

    @Test
    fun `a complete event passes`() {
        val report = EventValidator.validate(
            "access_fix_result",
            mapOf(
                "action_taken" to "request_role",
                "state_after" to "granted",
                "outcome" to "success",
            ),
        )
        assertTrue(report.summary, report.valid)
    }

    @Test
    fun `an event without an outcome is rejected`() {
        // This is the failure mode the old log had: intent with no outcome.
        val report = EventValidator.validate(
            "access_fix_result",
            mapOf("action_taken" to "request_role", "state_after" to "granted"),
        )
        assertFalse(report.valid)
        assertTrue(report.summary.contains("outcome"))
    }

    @Test
    fun `an unknown event is rejected`() {
        assertFalse(EventValidator.validate("something_invented", emptyMap()).valid)
    }

    @Test
    fun `an undeclared property is rejected`() {
        val report = EventValidator.validate(
            "access_fix_result",
            mapOf(
                "action_taken" to "x",
                "state_after" to "granted",
                "outcome" to "success",
                "whatever" to "1",
            ),
        )
        assertFalse(report.valid)
    }

    @Test
    fun `a value outside the enum is rejected`() {
        val report = EventValidator.validate(
            "access_fix_result",
            mapOf(
                "action_taken" to "x",
                "state_after" to "granted",
                "outcome" to "kinda worked",
            ),
        )
        assertFalse(report.valid)
    }

    @Test
    fun `blank required values count as missing`() {
        val report = EventValidator.validate(
            "access_fix_result",
            mapOf("action_taken" to "", "state_after" to "granted", "outcome" to "success"),
        )
        assertFalse(report.valid)
    }

    // ---------------- privacy (Part F) ----------------

    @Test
    fun `forbidden keys are blanked whatever the caller passes`() {
        listOf("phone", "number", "email", "name", "token", "body", "file_path")
            .forEach { key ->
                assertEquals(
                    "key '$key' should be redacted",
                    Redaction.REDACTED,
                    Redaction.scrub(key, "+91 98765 43210"),
                )
            }
    }

    @Test
    fun `a phone number is scrubbed even under an innocent key`() {
        val scrubbed = Redaction.scrub("detail", "call from +919876543210 failed")
        assertFalse(scrubbed.contains("9876543210"))
        assertTrue(scrubbed.contains("[number]"))
    }

    @Test
    fun `an email is scrubbed even under an innocent key`() {
        // 'detail' is not a forbidden key, so this exercises the value rule.
        val scrubbed = Redaction.scrub("detail", "reach me at someone@example.com")
        assertFalse(scrubbed.contains("someone@example.com"))
    }

    @Test
    fun `an absolute file path is scrubbed`() {
        val scrubbed = Redaction.scrub("detail", "wrote /data/user/0/app/files/x.m4a")
        assertFalse(scrubbed.contains("/data/user/0"))
    }

    @Test
    fun `short digit runs are not mistaken for phone numbers`() {
        // "401" is a status code, not a phone number.
        assertEquals("401", Redaction.scrub("status_code", "401"))
    }

    @Test
    fun `a masked number keeps only the last four digits`() {
        val masked = Redaction.maskNumber("+91 98765 43210")
        assertEquals("******3210", masked)
        assertFalse(masked.contains("98765"))
    }

    @Test
    fun `a user id is a salted hash, not the raw id`() {
        val raw = "user@example.com"
        val a = Redaction.hashUser(raw, "salt-one")
        val b = Redaction.hashUser(raw, "salt-two")
        assertNotNull(a)
        assertFalse(a!!.contains("example.com"))
        assertNotEquals("the same input must hash differently per install salt", a, b)
        assertEquals("hashing must be stable within one install", a, Redaction.hashUser(raw, "salt-one"))
    }

    @Test
    fun `a raw query is reduced to its length`() {
        assertEquals("13", Redaction.queryLength("pizza near me"))
        assertEquals("0", Redaction.queryLength(null))
    }

    // ---------------- envelope + codec ----------------

    @Test
    fun `every envelope field the standard requires is present`() {
        val json = LogEventCodec.encode(
            event("access_fix_result", mapOf("action_taken" to "x", "state_after" to "granted", "outcome" to "success")),
            envelope,
            "wifi",
        )
        EventSchema.ENVELOPE_REQUIRED.forEach { field ->
            assertTrue("missing envelope field '$field' in $json", json.contains("\"$field\""))
        }
        assertTrue(json.contains("\"schema_version\":\"${EventSchema.VERSION}\""))
    }

    @Test
    fun `the readable user code is stamped on every event`() {
        val json = LogEventCodec.encode(event("session_start", mapOf("start_type" to "cold")), envelope, null)
        assertTrue("user_code missing from $json", json.contains("\"user_code\":\"KV-4F9-K2Q\""))
    }

    @Test
    fun `the user code carries no personal data`() {
        // It is a random pseudonym: fixed shape, random body.
        assertTrue(envelope.userCode.matches(Regex("KV-[A-Z2-9]{3}-[A-Z2-9]{3}")))
    }

    @Test
    fun `the timestamp is ISO 8601 in UTC`() {
        val json = LogEventCodec.encode(event("session_start", mapOf("start_type" to "cold")), envelope, null)
        val match = Regex("\"timestamp\":\"([^\"]+)\"").find(json)
        assertNotNull(match)
        assertTrue(match!!.groupValues[1].endsWith("Z"))
        assertTrue(match.groupValues[1].matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
    }

    @Test
    fun `an event survives a round trip`() {
        val original = event(
            "access_fix_result",
            mapOf("action_taken" to "request_role", "state_after" to "granted", "outcome" to "success"),
            attemptId = "att_1234",
        )
        val decoded = LogEventCodec.decode(LogEventCodec.encode(original, envelope, null))
        assertNotNull(decoded)
        assertEquals(original.eventName, decoded!!.eventName)
        assertEquals("att_1234", decoded.attemptId)
        assertEquals(original.logLevel, decoded.logLevel)
        assertEquals("granted", decoded.properties["state_after"])
        assertEquals(original.timestampMs, decoded.timestampMs)
    }

    @Test
    fun `every catalogued event can be encoded`() {
        EventSchema.CATALOGUE.values.forEach { spec ->
            // Build the smallest valid instance: required fields filled with a
            // legal value (first enum member where the field is an enum).
            val props = spec.required.associateWith { key ->
                spec.enums[key]?.first() ?: "x"
            }
            assertTrue(
                "${spec.name} is not satisfiable",
                EventValidator.validate(spec.name, props).valid,
            )
            val json = LogEventCodec.encode(
                event(spec.name, props, level = LogLevel.INFO),
                envelope,
                null,
            )
            assertTrue(json.startsWith("{") && json.endsWith("}"))
            assertTrue(json.contains("\"event_name\":\"${spec.name}\""))
        }
    }

    @Test
    fun `a legacy free text line is not mistaken for an event`() {
        // The old format must not parse — it predates the schema.
        assertEquals(null, LogEventCodec.decode("2026-10-21 14:40:12 INFO  Screening  BLOCK"))
    }
}


    // ------------------------------------------------------ schema 1.1.0 --

    @Test
    fun `the six severities the taxonomy asks for are all present`() {
        assertEquals(
            setOf("trace", "debug", "info", "warn", "error", "fatal"),
            EventSchema.LOG_LEVELS,
        )
    }

    @Test
    fun `every category the taxonomy asks for is a real category`() {
        val expected = setOf(
            "application", "error", "security", "performance", "activity",
            "infrastructure", "network", "database", "audit", "analytics",
            "background", "trace",
        )
        assertEquals(expected, EventSchema.CATEGORIES)
    }

    @Test
    fun `every catalogued event declares a category from the vocabulary`() {
        EventSchema.CATALOGUE.values.forEach { spec ->
            assertTrue(
                "${spec.name} has category '${spec.category}'",
                spec.category in EventSchema.CATEGORIES,
            )
        }
    }

    @Test
    fun `the families the app had none of are all catalogued`() {
        listOf(
            "security_event", "background_job", "audit_event",
            "perf_sample", "db_query", "analytics_event", "trace_step",
        ).forEach { name ->
            assertTrue("$name is missing from the catalogue", EventSchema.isKnown(name))
        }
    }

    @Test
    fun `a security event carries its kind and outcome`() {
        assertTrue(
            EventValidator.validate(
                "security_event",
                mapOf("kind" to "tampered_file", "outcome" to "blocked"),
            ).valid,
        )
        assertFalse(
            EventValidator.validate("security_event", mapOf("kind" to "tampered_file")).valid,
            "an outcome is required",
        )
    }

    @Test
    fun `a background job outcome must be one of the four`() {
        assertTrue(
            EventValidator.validate(
                "background_job",
                mapOf("job" to "call_log_sync", "outcome" to "retry"),
            ).valid,
        )
        assertFalse(
            EventValidator.validate(
                "background_job",
                mapOf("job" to "call_log_sync", "outcome" to "maybe"),
            ).valid,
        )
    }

    @Test
    fun `the envelope carries category, request id and environment`() {
        val json = LogEventCodec.encode(
            LogEvent(
                eventName = "screen_view",
                logLevel = LogLevel.INFO,
                timestampMs = 0,
                sessionId = "s",
                screenOrRoute = "Home",
                requestId = "req_abc123",
            ),
            envelope,
            networkType = null,
        )
        assertTrue(json.contains("\"category\":\"application\""), json)
        assertTrue(json.contains("\"request_id\":\"req_abc123\""), json)
        assertTrue(json.contains("\"environment\":"), json)
    }

    @Test
    fun `a trace level survives the round trip`() {
        val json = LogEventCodec.encode(
            LogEvent(
                eventName = "trace_step",
                logLevel = LogLevel.TRACE,
                timestampMs = 0,
                sessionId = "s",
                screenOrRoute = "Home",
                properties = mapOf("step" to "begin"),
            ),
            envelope,
            networkType = null,
        )
        assertTrue(json.contains("\"log_level\":\"trace\""), json)
    }
