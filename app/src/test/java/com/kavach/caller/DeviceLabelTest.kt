package com.kavach.caller

import com.kavach.caller.data.diagnostics.SessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device labelling has failed twice in real exports, in opposite directions:
 *
 *   1. naive concatenation gave "TECNO TECNO LH8n"
 *   2. the first de-duplication dropped the model entirely and gave "TECNO"
 *
 * Both are pinned here so the fix for one cannot reintroduce the other.
 */
class DeviceLabelTest {

    @Test
    fun `the reported double-manufacturer case is cleaned up`() {
        assertEquals("TECNO LH8n", SessionContext.deviceLabel("TECNO", "TECNO LH8n"))
    }

    @Test
    fun `the model is never lost`() {
        // The regression: a de-duplication that returned only the maker.
        val label = SessionContext.deviceLabel("TECNO", "TECNO LH8n")
        assertTrue("model missing from '$label'", label.contains("LH8n"))
    }

    @Test
    fun `an ordinary device gets both fields`() {
        assertEquals("Google Pixel 7", SessionContext.deviceLabel("Google", "Pixel 7"))
    }

    @Test
    fun `a model that already carries the maker is not doubled`() {
        assertEquals("Samsung SM-G991B", SessionContext.deviceLabel("samsung", "Samsung SM-G991B"))
    }

    @Test
    fun `a missing model falls back to the maker`() {
        assertEquals("Xiaomi", SessionContext.deviceLabel("Xiaomi", ""))
    }

    @Test
    fun `a missing maker falls back to the model`() {
        assertEquals("Pixel 7", SessionContext.deviceLabel("", "Pixel 7"))
    }

    @Test
    fun `both missing gives a usable placeholder`() {
        assertEquals("unknown device", SessionContext.deviceLabel(null, null))
        assertEquals("unknown device", SessionContext.deviceLabel("  ", "  "))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("OnePlus 9", SessionContext.deviceLabel(" OnePlus ", " 9 "))
    }

    @Test
    fun `the result never contains a doubled maker`() {
        val cases = listOf(
            "TECNO" to "TECNO LH8n",
            "Xiaomi" to "Xiaomi 12",
            "OPPO" to "OPPO A54",
            "realme" to "realme 8",
        )
        cases.forEach { (maker, model) ->
            val label = SessionContext.deviceLabel(maker, model)
            val makerCount = Regex(Regex.escape(maker), RegexOption.IGNORE_CASE).findAll(label).count()
            assertEquals("'$label' repeats the maker $makerCount times", 1, makerCount)
        }
    }
}
