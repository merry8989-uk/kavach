package com.kavach.caller.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heuristic spam scorer (F9.3). Runs on-device, instant, no network.
 * The cloud model refines this when the user opts in; this keeps the app
 * useful offline.
 */
@Singleton
class SpamScorer @Inject constructor() {

    fun score(
        number: String?,
        reports: Int = 0,
        isContact: Boolean = false,
        prefixMatchesSpam: Boolean = false,
    ): Int {
        if (isContact) return 0
        var score = 0
        score += (reports.coerceAtMost(200) / 2)          // crowd reports
        if (prefixMatchesSpam) score += 25
        if (isPremiumOrShortcode(number)) score += 15
        if (isSequentialOrRepeated(number)) score += 20
        return score.coerceIn(0, 100)
    }

    private fun isPremiumOrShortcode(number: String?): Boolean {
        val d = number?.filter { it.isDigit() } ?: return false
        return d.startsWith("1800") || (d.length in 3..6)
    }

    private fun isSequentialOrRepeated(number: String?): Boolean {
        val d = number?.filter { it.isDigit() }?.takeLast(10) ?: return false
        if (d.length < 10) return false
        if (d.toSet().size <= 2) return true                       // 99999 99999
        val ascending = "01234567890123456789".contains(d)
        val descending = "98765432109876543210".contains(d)
        return ascending || descending
    }
}
