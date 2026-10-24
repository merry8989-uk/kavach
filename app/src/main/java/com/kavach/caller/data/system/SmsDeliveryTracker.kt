package com.kavach.caller.data.system

import android.app.Activity
import android.app.PendingIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened to the messages this app sent.
 *
 * SMS has two separate acknowledgements and they mean different things:
 *
 * - **Sent** — the radio accepted the message. Reliable, fast, and the only one
 *   worth retrying on.
 * - **Delivered** — the far handset received it. A carrier courtesy that can take
 *   minutes and on many networks never arrives at all. Reported when it comes,
 *   never waited on.
 *
 * Keeping them apart is the point. A screen that shows one "Delivered" tick for
 * both is telling the user something it does not know.
 */
@Singleton
class SmsDeliveryTracker @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) {

    enum class State { QUEUED, SENT, DELIVERED, FAILED }

    data class Record(
        val ref: Int,
        val address: String,
        val text: String,
        val subscriptionId: Int?,
        val autoRetry: Boolean,
        val state: State = State.QUEUED,
        val reason: String? = null,
        /** True once a retry has been attempted, so it only ever happens once. */
        val retried: Boolean = false,
    )

    private val counter = AtomicInteger(1)
    private val records = ConcurrentHashMap<Int, Record>()

    private val _recent = MutableStateFlow<List<Record>>(emptyList())
    val recent = _recent.asStateFlow()

    /**
     * Register a send and return its reference.
     *
     * The text is kept so a retry can resend it. It is held in memory only and
     * dropped once the record settles — a queue of message bodies on disk is a
     * liability with no upside.
     */
    fun nextRef(
        address: String,
        text: String,
        subscriptionId: Int?,
        autoRetry: Boolean,
    ): Int {
        val ref = counter.getAndIncrement()
        records[ref] = Record(ref, address, text, subscriptionId, autoRetry)
        publish()
        return ref
    }

    fun sentIntent(ref: Int): PendingIntent = buildIntent("kavach.sms.SENT", ref, Activity.RESULT_OK)

    fun deliveredIntent(ref: Int): PendingIntent = buildIntent("kavach.sms.DELIVERED", ref, 0)

    private fun buildIntent(action: String, ref: Int, flags: Int): PendingIntent {
        val intent = android.content.Intent(context, SmsDeliveryReceiver::class.java).apply {
            this.action = "$action.$ref"
            putExtra(SmsDeliveryReceiver.EXTRA_REF, ref)
        }
        return PendingIntent.getBroadcast(
            context,
            ref * 2 + if (action.contains("DELIVERED")) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or flags,
        )
    }

    fun markSent(ref: Int, ok: Boolean, reason: String?) {
        val record = records[ref] ?: return
        records[ref] = if (ok) {
            record.copy(state = State.SENT)
        } else {
            record.copy(state = State.FAILED, reason = reason)
        }
        publish()
    }

    /**
     * A delivery report arrived.
     *
     * Only moves the record forward. A carrier can deliver the report after a
     * retry has already superseded the message, and a late report must not
     * rewrite a settled record.
     */
    fun markDelivered(ref: Int) {
        val record = records[ref] ?: return
        if (record.state == State.SENT || record.state == State.QUEUED) {
            records[ref] = record.copy(state = State.DELIVERED)
            publish()
        }
    }

    fun record(ref: Int): Record? = records[ref]

    /** Mark that a retry was attempted, so it happens at most once. */
    fun markRetried(ref: Int) {
        records[ref]?.let { records[ref] = it.copy(retried = true) }
    }

    /** Drop a settled record, and with it the message body it was holding. */
    fun forget(ref: Int) {
        val record = records[ref] ?: return
        if (record.state != State.QUEUED) {
            records.remove(ref)
            publish()
        }
    }

    private fun publish() {
        _recent.value = records.values.sortedByDescending { it.ref }.take(50)
    }
}
