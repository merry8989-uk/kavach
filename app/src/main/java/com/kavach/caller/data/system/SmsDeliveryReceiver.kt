package com.kavach.caller.data.system

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles the two acknowledgements SMS gives.
 *
 * The retry lives here rather than in [SmsSender] because the failure is only
 * known now. `sendTextMessage` returns before the radio has decided anything, so
 * a retry attempted at the call site would be guessing.
 *
 * The retry is **once**, and only for a result the platform actually calls an
 * error. `RESULT_ERROR_GENERIC_FAILURE` in particular arrives for a lot of
 * things that are not worth retrying — no credit, no service, a blocked
 * destination — and a retry loop on those burns money and battery while changing
 * nothing.
 */
@AndroidEntryPoint
class SmsDeliveryReceiver : BroadcastReceiver() {

    @Inject lateinit var tracker: SmsDeliveryTracker

    @Inject lateinit var sender: SmsSender

    override fun onReceive(context: Context, intent: Intent) {
        val ref = intent.getIntExtra(EXTRA_REF, -1)
        if (ref < 0) return
        val record = tracker.record(ref) ?: return

        when {
            intent.action?.startsWith(ACTION_SENT) == true -> handleSent(record, resultCode)
            intent.action?.startsWith(ACTION_DELIVERED) == true -> handleDelivered(ref)
        }
    }

    private fun handleSent(record: SmsDeliveryTracker.Record, resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            tracker.markSent(record.ref, ok = true, reason = null)
            return
        }

        val reason = describe(resultCode)
        tracker.markSent(record.ref, ok = false, reason = reason)

        if (record.autoRetry && !record.retried && isWorthRetrying(resultCode)) {
            tracker.markRetried(record.ref)
            sender.send(
                address = record.address,
                text = record.text,
                subscriptionId = record.subscriptionId,
                deliveryReports = false,
                // A retry of a retry is not a retry, it is a loop.
                autoRetry = false,
            )
        }
    }

    private fun handleDelivered(ref: Int) {
        // The result code here is the *carrier's*, and most networks send 0.
        // Anything else is reported but not acted on: there is nothing useful to
        // do about a delivery report that says the far handset refused it.
        tracker.markDelivered(ref)
    }

    /**
     * Whether this result is worth one more attempt.
     *
     * Narrow on purpose. A generic failure covers no credit, no service and a
     * blocked destination — retrying any of those spends the user's money to
     * produce the same error.
     */
    private fun isWorthRetrying(resultCode: Int): Boolean = when (resultCode) {
        SmsManager.RESULT_ERROR_NO_SERVICE,
        SmsManager.RESULT_ERROR_NULL_PDU,
        -> true
        else -> false
    }

    private fun describe(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "The network refused it."
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service."
        SmsManager.RESULT_ERROR_NULL_PDU -> "The network sent a bad response."
        SmsManager.RESULT_ERROR_RADIO_OFF -> "The radio is off — turn off airplane mode."
        else -> "The message could not be sent (code $resultCode)."
    }

    companion object {
        const val EXTRA_REF = "kavach.sms.ref"
        const val ACTION_SENT = "kavach.sms.SENT"
        const val ACTION_DELIVERED = "kavach.sms.DELIVERED"
    }
}
