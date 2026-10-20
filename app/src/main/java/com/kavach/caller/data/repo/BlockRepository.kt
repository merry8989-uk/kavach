package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.BlockRuleDao
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.local.entity.BlockRuleEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns block rules and evaluates them against an incoming number.
 * Rule evaluation itself lives in [RuleMatcher] so it stays unit-testable.
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
        val rules = dao.enabledRules().sortedBy { it.type.ordinal }
        for (rule in rules) {
            if (RuleMatcher.matches(rule, number, isContact)) {
                return Decision(rule.action, rule)
            }
        }
        return Decision(BlockAction.ALLOW, null)
    }

    data class Decision(val action: BlockAction, val rule: BlockRuleEntity?)
}
