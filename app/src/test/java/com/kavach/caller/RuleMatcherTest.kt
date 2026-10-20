package com.kavach.caller

import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import com.kavach.caller.data.repo.RuleMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {

    private fun rule(type: RuleType, pattern: String) =
        BlockRuleEntity(pattern = pattern, type = type)

    @Test
    fun countryRule_matchesByCallingCode() {
        assertTrue(RuleMatcher.matches(rule(RuleType.COUNTRY, "92"), "+923001234567", false))
        assertFalse(RuleMatcher.matches(rule(RuleType.COUNTRY, "92"), "+919876543210", false))
    }

    @Test
    fun prefixRule_matches() {
        assertTrue(RuleMatcher.matches(rule(RuleType.PREFIX, "1800"), "1800123456", false))
        assertFalse(RuleMatcher.matches(rule(RuleType.PREFIX, "1800"), "9876543210", false))
    }

    @Test
    fun wildcardRule_escapesRegexMetacharacters() {
        // Regression: the old matcher only escaped '.' and '*', so '+' was
        // treated as a regex quantifier and matched the wrong numbers.
        val r = rule(RuleType.WILDCARD, "+919*")
        assertTrue(RuleMatcher.matches(r, "+919876543210", false))
        assertFalse(RuleMatcher.matches(r, "+918876543210", false))
    }

    @Test
    fun regexRule_matches() {
        assertTrue(RuleMatcher.matches(rule(RuleType.REGEX, "\\+91\\d{10}"), "+919876543210", false))
    }

    @Test
    fun privateRule_matchesWithheldNumbers() {
        assertTrue(RuleMatcher.matches(rule(RuleType.PRIVATE, ""), null, false))
        assertFalse(RuleMatcher.matches(rule(RuleType.PRIVATE, ""), "9876543210", false))
    }

    @Test
    fun nonContactRule_matchesOnlyUnknownNumbers() {
        assertTrue(RuleMatcher.matches(rule(RuleType.NON_CONTACT, ""), "9876543210", false))
        assertFalse(RuleMatcher.matches(rule(RuleType.NON_CONTACT, ""), "9876543210", true))
    }

    @Test
    fun rangeRule_matchesInclusive() {
        val r = rule(RuleType.RANGE, "9000000000-9999999999")
        assertTrue(RuleMatcher.matches(r, "9876543210", false))
        assertFalse(RuleMatcher.matches(r, "8000000000", false))
    }
}
