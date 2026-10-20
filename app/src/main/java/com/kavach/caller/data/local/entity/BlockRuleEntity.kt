package com.kavach.caller.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** What happens when a call matches a rule. */
enum class BlockAction { BLOCK, SILENCE, SEND_TO_AI, ALLOW }

/** How a rule's [pattern] is interpreted. */
enum class RuleType { EXACT, PREFIX, WILDCARD, REGEX, RANGE, COUNTRY, PRIVATE, NON_CONTACT }

@Entity(tableName = "block_rules")
data class BlockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String = "",
    val type: RuleType = RuleType.EXACT,
    val action: BlockAction = BlockAction.BLOCK,
    val label: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
