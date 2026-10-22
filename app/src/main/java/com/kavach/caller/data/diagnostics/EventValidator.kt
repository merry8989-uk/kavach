package com.kavach.caller.data.diagnostics

/**
 * Validates an event against [EventSchema] before it is written.
 *
 * This runs in CI and at runtime, because a log that
 * silently records a malformed event is worse than no log at all. Anything that
 * fails validation is dropped and a `warn` is raised about the drop itself, so
 * the gap is visible rather than hidden.
 */
object EventValidator {

    private val SNAKE_CASE = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")

    data class Report(val valid: Boolean, val problems: List<String>) {
        val summary: String get() = problems.joinToString("; ")
    }

    fun validate(eventName: String, properties: Map<String, String>): Report {
        val problems = mutableListOf<String>()

        val spec = EventSchema.specFor(eventName)
        if (spec == null) {
            return Report(false, listOf("unknown event_name '$eventName'"))
        }

        spec.required.forEach { key ->
            val value = properties[key]
            if (value == null) {
                problems += "missing required '$key'"
            } else if (value.isBlank()) {
                problems += "blank required '$key'"
            }
        }

        properties.keys.forEach { key ->
            if (key !in spec.allowed) problems += "unexpected property '$key'"
        }

        spec.enums.forEach { (key, allowed) ->
            val value = properties[key]
            if (value != null && value !in allowed) {
                problems += "'$key'='$value' is not one of ${allowed.joinToString("/")}"
            }
        }

        return Report(problems.isEmpty(), problems)
    }

    /** Validate a whole catalogue, used by the test suite. */
    fun validateCatalogue(): List<String> {
        val problems = mutableListOf<String>()
        EventSchema.CATALOGUE.values.forEach { spec ->
            // snake_case. Zero underscore groups is allowed on purpose: the
            // standard's own catalogue contains single-word events such as
            // "crash" and "anr". camelCase and spaces are still rejected.
            if (!spec.name.matches(SNAKE_CASE)) {
                problems += "${spec.name} is not snake_case object_action"
            }
            if (spec.required.isEmpty()) {
                problems += "${spec.name} requires nothing — it would prove nothing"
            }
            if (spec.required.any { it in EventSchema.ENVELOPE_REQUIRED }) {
                problems += "${spec.name} redeclares an envelope field"
            }
            spec.enums.forEach { (key, allowed) ->
                if (allowed.isEmpty()) problems += "${spec.name}.$key has an empty enum"
            }
        }
        return problems
    }
}
