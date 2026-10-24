package com.kavach.caller.data.system

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sending SMS.
 *
 * The previous version called `sendTextMessage(address, null, text, null, null)`
 * and reported `Sent` the moment the call returned. That is not the same as the
 * message having been sent — `sendTextMessage` hands the message to the radio and
 * returns; the radio can still reject it. So the app said "Sent" for messages that
 * were never delivered, and there was nothing to retry because nothing ever
 * reported a failure.
 *
 * Both of those are fixed by passing the two intents the platform offers, and
 * they are different things:
 *
 * - **sentIntent** — did the radio accept it? This is a real signal, and it is
 *   what [autoRetry] acts on.
 * - **deliveryIntent** — did the far handset receive it? This is a carrier
 *   courtesy, it can take a long time, and on many networks it never arrives at
 *   all. It is off unless the user asks for it, and the settings screen says what
 *   it does and does not mean.
 */
@Singleton
class SmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val delivery: SmsDeliveryTracker,
) {

    sealed interface Result {
        /** Handed to the radio. Whether it was accepted arrives separately. */
        data class Queued(val messageRef: Int) : Result
        data class Failed(val reason: String) : Result
        data object Sent : Result
    }

    /**
     * Send [text] to [address].
     *
     * @param subscriptionId the SIM to send from, when the caller knows it.
     * @param deliveryReports ask the carrier for a delivery report.
     * @param autoRetry resend once if the radio rejects the first attempt.
     */
    fun send(
        address: String,
        text: String,
        subscriptionId: Int? = null,
        deliveryReports: Boolean = false,
        autoRetry: Boolean = true,
    ): Result {
        if (address.isBlank()) return Result.Failed("No recipient.")
        if (text.isBlank()) return Result.Failed("Nothing to send.")
        if (!canSend()) {
            return Result.Failed("SMS permission is not granted, so the message was not sent.")
        }

        val manager = resolveManager(subscriptionId)
            ?: return Result.Failed("No SMS service is available on this device.")

        return try {
            // Split long messages rather than letting the carrier truncate them.
            val parts = manager.divideMessage(text)
            val ref = delivery.nextRef(address, text, subscriptionId, autoRetry)

            if (parts.size <= 1) {
                manager.sendTextMessage(
                    address, null, text,
                    delivery.sentIntent(ref),
                    if (deliveryReports) delivery.deliveredIntent(ref) else null,
                )
            } else {
                manager.sendMultipartTextMessage(
                    address, null, parts,
                    ArrayList(List(parts.size) { delivery.sentIntent(ref) }),
                    if (deliveryReports) {
                        ArrayList(List(parts.size) { delivery.deliveredIntent(ref) })
                    } else {
                        null
                    },
                )
            }
            // Queued, not sent. The distinction is the whole point of the change:
            // the caller is told the message left, and the tracker reports what
            // happened to it.
            Result.Queued(ref)
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "The message could not be sent.")
        }
    }

    fun canSend(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun resolveManager(subscriptionId: Int?): SmsManager? = runCatching {
        if (subscriptionId != null && subscriptionId >= 0) {
            return@runCatching SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val subs = context.getSystemService(SubscriptionManager::class.java)
            val default = subs?.defaultSmsSubscriptionId ?: -1
            if (default >= 0) return@runCatching SmsManager.getSmsManagerForSubscriptionId(default)
        }
        SmsManager.getDefault()
    }.getOrNull()

    /** A PendingIntent that is distinct per request, so results can be matched. */
    internal fun pendingIntent(action: String, ref: Int, flags: Int): PendingIntent {
        val intent = Intent(context, SmsDeliveryReceiver::class.java).apply {
            this.action = "$action.$ref"
            putExtra(SmsDeliveryReceiver.EXTRA_REF, ref)
        }
        return PendingIntent.getBroadcast(
            context,
            ref * 2 + if (action.endsWith("delivered")) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or flags,
        )
    }
}
