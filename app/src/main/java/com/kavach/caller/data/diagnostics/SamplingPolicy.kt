package com.kavach.caller.data.diagnostics

/**
 * Decides whether a high-volume event survives, given how busy the app is.
 *
 * This exists because a fixed sampling rate was silently destroying the log.
 * A real export showed sixteen `screen_view` events across ten sessions, with
 * four sessions having none at all — the sampling was throwing away three
 * quarters of the one event that explains what a user actually did.
 *
 * Kavach emits roughly six events per session. Sampling a log that small is
 * pure loss. So the rule is volume-aware: keep everything while the app is
 * quiet, and start sampling only once the rate says there is too much to keep.
 *
 * Kept pure and separate so the behaviour can be tested without a device.
 */
object SamplingPolicy {

    /** One in this many is kept, once the app is chatty. */
    const val INFO_SAMPLE_RATE = 4L

    /** Events per minute above which info events start being sampled. */
    const val CHATTY_EVENTS_PER_MINUTE = 120.0

    /** How far back the rate is measured. */
    const val RATE_WINDOW_MS = 60_000L

    /**
     * Should this event be kept?
     *
     * @param ratePerMinute how fast events are currently being written.
     * @param sampleTick a monotonically changing number, used as the sampler.
     */
    fun keep(ratePerMinute: Double, sampleTick: Long): Boolean {
        if (ratePerMinute <= CHATTY_EVENTS_PER_MINUTE) return true
        return sampleTick % INFO_SAMPLE_RATE == 0L
    }

    /** The rate implied by [count] writes inside the window. */
    fun ratePerMinute(count: Int): Double = count * (60_000.0 / RATE_WINDOW_MS)
}
