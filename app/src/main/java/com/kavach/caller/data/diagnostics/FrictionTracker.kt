package com.kavach.caller.data.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * User-friendliness signals (standard Part E).
 *
 * "These events directly expose friction. Capture them on both app and web —
 * they are how you find UX problems before users complain."
 *
 * Kept as a small object a screen can hold so the signals are produced by real
 * interaction rather than sprinkled through UI code:
 *
 *  - `rage_tap`     — 3+ taps on the same element inside 2s
 *  - `dead_click`   — a tap that changed nothing at all
 *  - `back_button_spam` — 3+ back presses inside 2s
 *
 * Each signal fires once per burst, so a frustrated user does not flood the log.
 */
class FrictionTracker(
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticsRepository,
) {
    private val taps = mutableMapOf<String, MutableList<Long>>()
    private val backs = mutableListOf<Long>()

    /**
     * Record a tap.
     *
     * @param stateChanged whether anything actually happened as a result.
     */
    fun onTap(element: String, stateChanged: Boolean) {
        val now = System.currentTimeMillis()
        val window = taps.getOrPut(element) { mutableListOf() }
        window.removeAll { now - it > WINDOW_MS }
        window += now

        when {
            window.size >= RAGE_THRESHOLD -> {
                window.clear()
                scope.launch { diagnostics.rageTap(element = element, tapCount = RAGE_THRESHOLD) }
            }
            // A single tap that changed nothing is a dead control.
            !stateChanged -> scope.launch { diagnostics.deadClick(element = element) }
        }
    }

    /** Record a back press. */
    fun onBack() {
        val now = System.currentTimeMillis()
        backs.removeAll { now - it > WINDOW_MS }
        backs += now
        if (backs.size >= RAGE_THRESHOLD) {
            val count = backs.size
            backs.clear()
            scope.launch { diagnostics.backButtonSpam(pressCount = count) }
        }
    }

    /** The user left a flow without finishing it. */
    fun onDropOff(step: String, lastEvent: String? = null) {
        scope.launch { diagnostics.dropOff(step = step, lastEvent = lastEvent) }
    }

    private companion object {
        const val WINDOW_MS = 2_000L
        const val RAGE_THRESHOLD = 3
    }
}
