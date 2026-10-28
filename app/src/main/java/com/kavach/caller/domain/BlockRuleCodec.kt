package com.kavach.caller.domain

import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Moving block rules in and out of the app.
 *
 * ## Why JSON rather than a CSV
 *
 * A rule has a pattern, a type, an action, a label and an enabled flag. In CSV the
 * pattern `+91*` and the label `Telemarketing, India` fight over the comma, and
 * the escaping rules that fix it are the ones people get wrong when they edit the
 * file by hand. JSON has no such ambiguity.
 *
 * ## What is deliberately not exported
 *
 * **No call history, no contact names, no timestamps of anything except when a
 * rule was made.** A block list is a statement about which numbers the user does
 * not want to hear from — exporting more than the rules would turn a backup into a
 * disclosure.
 */
object BlockRuleCodec {

    @Serializable
    private data class Wire(
        val version: Int = 1,
        val pattern: String,
        val type: String,
        val action: String,
        val label: String? = null,
        val enabled: Boolean = true,
    )

    @Serializable
    private data class Document(
        val app: String = "kavach",
        val kind: String = "block-rules",
        val version: Int = 1,
        val rules: List<Wire> = emptyList(),
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serialise every rule. */
    fun export(rules: List<BlockRuleEntity>): String = json.encodeToString(
        Document.serializer(),
        Document(
            rules = rules.map {
                Wire(
                    pattern = it.pattern,
                    type = it.type.name,
                    action = it.action.name,
                    label = it.label,
                    enabled = it.enabled,
                )
            },
        ),
    )

    /** What an import produced, including what it refused. */
    data class Imported(
        val rules: List<BlockRuleEntity>,
        val skipped: List<String>,
    )

    /**
     * Read a file.
     *
     * **Refuses rather than guesses.** An unknown `type` or `action` is skipped and
     * named, not defaulted — a rule silently imported as `ALLOW` when it said
     * something the reader did not understand would stop blocking a number the
     * user meant to block.
     *
     * A rule with a blank pattern is skipped too: an empty `EXACT` pattern matches
     * nothing, and an empty `PREFIX` pattern matches **everything**, which would
     * block every call on the phone.
     */
    fun import(text: String): Imported {
        val document = runCatching { json.decodeFromString(Document.serializer(), text) }
            .getOrElse { return Imported(emptyList(), listOf("That file is not a Kavach block-rule export.")) }

        if (document.kind != "block-rules") {
            return Imported(emptyList(), listOf("That file is not a Kavach block-rule export."))
        }

        val kept = mutableListOf<BlockRuleEntity>()
        val skipped = mutableListOf<String>()

        document.rules.forEachIndexed { index, wire ->
            val type = RuleType.entries.firstOrNull { it.name.equals(wire.type, ignoreCase = true) }
            val action = BlockAction.entries.firstOrNull { it.name.equals(wire.action, ignoreCase = true) }

            when {
                wire.pattern.isBlank() -> skipped += "rule ${index + 1}: empty pattern"
                type == null -> skipped += "rule ${index + 1}: unknown type \"${wire.type}\""
                action == null -> skipped += "rule ${index + 1}: unknown action \"${wire.action}\""
                // An empty prefix or wildcard matches every number there is.
                (type == RuleType.PREFIX || type == RuleType.WILDCARD) && wire.pattern.isBlank() ->
                    skipped += "rule ${index + 1}: an empty ${type.name.lowercase()} would match every call"
                else -> kept += BlockRuleEntity(
                    pattern = wire.pattern.trim(),
                    type = type,
                    action = action,
                    label = wire.label?.trim()?.ifEmpty { null },
                    enabled = wire.enabled,
                    // A fresh timestamp: the rule is new to this install, and
                    // carrying the old one would misrepresent when it was made.
                    createdAt = System.currentTimeMillis(),
                )
            }
        }
        return Imported(kept, skipped)
    }
}
