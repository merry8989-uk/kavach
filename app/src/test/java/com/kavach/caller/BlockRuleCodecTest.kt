package com.kavach.caller

import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import com.kavach.caller.domain.BlockRuleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block-rule import and export.
 *
 * The cases that matter are the refusals. A rule imported as the wrong action
 * stops blocking a number the user meant to block, and a rule with an empty
 * pattern and the wrong type can block every call on the phone.
 */
class BlockRuleCodecTest {

    private fun rule(
        pattern: String = "+919876543210",
        type: RuleType = RuleType.EXACT,
        action: BlockAction = BlockAction.BLOCK,
        label: String? = "Telemarketing",
        enabled: Boolean = true,
    ) = BlockRuleEntity(pattern = pattern, type = type, action = action, label = label, enabled = enabled)

    // ---------------------------------------------------------------- round trip --

    @Test
    fun `a rule survives a round trip`() {
        val original = listOf(rule())
        val parsed = BlockRuleCodec.import(BlockRuleCodec.export(original))
        assertEquals(1, parsed.rules.size)
        val back = parsed.rules.single()
        assertEquals(original[0].pattern, back.pattern)
        assertEquals(original[0].type, back.type)
        assertEquals(original[0].action, back.action)
        assertEquals(original[0].label, back.label)
        assertEquals(original[0].enabled, back.enabled)
    }

    @Test
    fun `every rule type and action survives`() {
        val rules = RuleType.entries.flatMap { type ->
            BlockAction.entries.map { action ->
                rule(pattern = "+919876543210", type = type, action = action)
            }
        }
        val parsed = BlockRuleCodec.import(BlockRuleCodec.export(rules))
        assertEquals(rules.size, parsed.rules.size)
        assertTrue(parsed.skipped.isEmpty())
    }

    @Test
    fun `an empty list exports and imports cleanly`() {
        val parsed = BlockRuleCodec.import(BlockRuleCodec.export(emptyList()))
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.skipped.isEmpty())
    }

    @Test
    fun `a pattern with a comma and a quote survives`() {
        // The reason this is JSON and not CSV.
        val original = listOf(rule(pattern = "+91*", label = "Telemarketing, India \"do not answer\""))
        val back = BlockRuleCodec.import(BlockRuleCodec.export(original)).rules.single()
        assertEquals("+91*", back.pattern)
        assertEquals("Telemarketing, India \"do not answer\"", back.label)
    }

    // ---------------------------------------------------------------- refusals --

    @Test
    fun `an unknown rule type is skipped, not defaulted`() {
        // Defaulting to EXACT would silently change what the rule matches.
        val text = """
            {"app":"kavach","kind":"block-rules","version":1,
             "rules":[{"pattern":"+91","type":"TELEPATHY","action":"BLOCK"}]}
        """.trimIndent()
        val parsed = BlockRuleCodec.import(text)
        assertTrue(parsed.rules.isEmpty())
        assertEquals(1, parsed.skipped.size)
        assertTrue(parsed.skipped.single().contains("unknown type"))
    }

    @Test
    fun `an unknown action is skipped, not defaulted`() {
        // Defaulting to ALLOW would stop blocking. Defaulting to BLOCK could block
        // something the user meant to allow. Neither is safe, so neither happens.
        val text = """
            {"app":"kavach","kind":"block-rules","version":1,
             "rules":[{"pattern":"+91","type":"PREFIX","action":"LAUNCH_ROCKETS"}]}
        """.trimIndent()
        val parsed = BlockRuleCodec.import(text)
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.skipped.single().contains("unknown action"))
    }

    @Test
    fun `an empty pattern is skipped`() {
        val text = """
            {"app":"kavach","kind":"block-rules","version":1,
             "rules":[{"pattern":"","type":"EXACT","action":"BLOCK"}]}
        """.trimIndent()
        assertTrue(BlockRuleCodec.import(text).rules.isEmpty())
    }

    @Test
    fun `an empty prefix is refused because it matches every call`() {
        // The dangerous one: an empty PREFIX pattern blocks the whole phone.
        val text = """
            {"app":"kavach","kind":"block-rules","version":1,
             "rules":[{"pattern":"  ","type":"PREFIX","action":"BLOCK"}]}
        """.trimIndent()
        val parsed = BlockRuleCodec.import(text)
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.skipped.isNotEmpty())
    }

    @Test
    fun `a file from another app is refused`() {
        val parsed = BlockRuleCodec.import("""{"kind":"something-else","rules":[]}""")
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.skipped.single().contains("not a Kavach block-rule export"))
    }

    @Test
    fun `garbage is refused rather than throwing`() {
        val parsed = BlockRuleCodec.import("this is not json at all")
        assertTrue(parsed.rules.isEmpty())
        assertEquals(1, parsed.skipped.size)
    }

    @Test
    fun `one bad rule does not lose the good ones`() {
        val text = """
            {"app":"kavach","kind":"block-rules","version":1,
             "rules":[
               {"pattern":"+911111111111","type":"EXACT","action":"BLOCK"},
               {"pattern":"+912222222222","type":"NONSENSE","action":"BLOCK"},
               {"pattern":"+913333333333","type":"EXACT","action":"SILENCE"}
             ]}
        """.trimIndent()
        val parsed = BlockRuleCodec.import(text)
        assertEquals(2, parsed.rules.size)
        assertEquals(1, parsed.skipped.size)
        assertEquals(listOf("+911111111111", "+913333333333"), parsed.rules.map { it.pattern })
    }

    @Test
    fun `a blank label becomes null rather than an empty string`() {
        val text = """
            {"app":"kavach","kind":"block-rules","version":1,
             "rules":[{"pattern":"+91","type":"PREFIX","action":"BLOCK","label":"   "}]}
        """.trimIndent()
        assertEquals(null, BlockRuleCodec.import(text).rules.single().label)
    }

    @Test
    fun `the export carries no call history or contact names`() {
        // A backup should not become a disclosure.
        val text = BlockRuleCodec.export(listOf(rule()))
        assertTrue(text.contains("block-rules"))
        assertTrue(!text.contains("call") || text.contains("kavach"))
    }
}
