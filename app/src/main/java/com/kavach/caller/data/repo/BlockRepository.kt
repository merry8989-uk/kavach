package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.BlockRuleDao
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import com.kavach.caller.util.PhoneUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns block rules and evaluates them against an incoming number.
 * The rule engine is intentionally pure (no Android deps) so it is unit-testable.
 */
@Singleton
class BlockRepository @Inject constructor(
    private val dao: BlockRuleDao,
) {
    fun observeAll(): Flow<List<BlockRuleEntity>> = dao.observeAll()

    suspend fun upsert(rule: BlockRuleEntity): Long = dao.upsert(rule)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    /** First matching rule wins, ordered by specificity (exact before wildcard). */
    suspend fun decide(number: String?, isContact: Boolean): Decision {
        val rules = dao.enabledRules()
        val normalized = PhoneUtils.normalize(number)
        val e164 = PhoneUtils.toE164(number ?: "")
        val sorted = rules.sortedBy { it.type.ordinal }

        for (rule in sorted) {
            if (matches(rule, normalized, e164, number, isContact)) {
                return Decision(rule.action, rule)
            }
        }
        return Decision(BlockAction.ALLOW, null)
    }

    private fun matches(
        rule: BlockRuleEntity,
        normalized: String,
        e164: String,
        raw: String?,
        isContact: Boolean,
    ): Boolean = when (rule.type) {
        RuleType.EXACT -> PhoneUtils.sameNumber(rule.pattern, raw)
        RuleType.PREFIX -> normalized.startsWith(PhoneUtils.normalize(rule.pattern))
        RuleType.WILDCARD -> wildcardToRegex(rule.pattern).matches(e164)
        RuleType.REGEX -> runCatching { Regex(rule.pattern).containsMatchIn(e164) }.getOrDefault(false)
        RuleType.RANGE -> inRange(rule.pattern, normalized)
        RuleType.COUNTRY -> e164.startsWith("+${PhoneUtils.normalize(rule.pattern)}")
        RuleType.PRIVATE -> PhoneUtils.isPrivate(raw)
        RuleType.NON_CONTACT -> !isContact
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val escaped = pattern.replace(".", "\\.").replace("*", ".*")
        return Regex("^$escaped$")
    }

    private fun inRange(pattern: String, value: String): Boolean {
        // pattern format: "start-end"
        val parts = pattern.split("-")
        if (parts.size != 2) return false
        val v = value.toLongOrNull() ?: return false
        val start = parts[0].trim().toLongOrNull() ?: return false
        val end = parts[1].trim().toLongOrNull() ?: return false
        return v in start..end
    }

    data class Decision(val action: BlockAction, val rule: BlockRuleEntity?)
}
