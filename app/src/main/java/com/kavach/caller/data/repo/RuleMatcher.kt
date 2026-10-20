package com.kavach.caller.data.repo

import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import com.kavach.caller.util.PhoneUtils

/**
 * Pure, Android-free block-rule evaluation.
 *
 * Extracted from [BlockRepository] so it can be unit-tested on the JVM.
 * Rule types: exact, prefix, wildcard, regex, range, country, private,
 * non-contact. First match wins (callers sort by specificity).
 */
object RuleMatcher {

    fun matches(rule: BlockRuleEntity, raw: String?, isContact: Boolean): Boolean {
        val normalized = PhoneUtils.normalize(raw)
        val e164 = PhoneUtils.toE164(raw ?: "")
        return when (rule.type) {
            RuleType.EXACT -> PhoneUtils.sameNumber(rule.pattern, raw)
            RuleType.PREFIX -> normalized.isNotEmpty() &&
                normalized.startsWith(PhoneUtils.normalize(rule.pattern))
            RuleType.WILDCARD -> wildcardToRegex(rule.pattern).matches(e164)
            RuleType.REGEX -> runCatching { Regex(rule.pattern).containsMatchIn(e164) }
                .getOrDefault(false)
            RuleType.RANGE -> inRange(rule.pattern, normalized)
            RuleType.COUNTRY -> e164.startsWith("+" + PhoneUtils.normalize(rule.pattern))
            RuleType.PRIVATE -> PhoneUtils.isPrivate(raw)
            RuleType.NON_CONTACT -> !isContact
        }
    }

    /**
     * Turn a wildcard pattern into a regex, escaping every metacharacter
     * except '*'. (The old version only escaped '.' and '*', so a pattern
     * containing '+', '?', '(' etc. could compile to the wrong regex or throw.)
     */
    fun wildcardToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (c in pattern) {
            if (c == '*') sb.append(".*") else sb.append(Regex.escape(c.toString()))
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    /** Range pattern format: "start-end" (inclusive), matched against digits. */
    fun inRange(pattern: String, value: String): Boolean {
        val parts = pattern.split("-")
        if (parts.size != 2) return false
        val v = value.toLongOrNull() ?: return false
        val start = parts[0].trim().toLongOrNull() ?: return false
        val end = parts[1].trim().toLongOrNull() ?: return false
        return v in start..end
    }
}
