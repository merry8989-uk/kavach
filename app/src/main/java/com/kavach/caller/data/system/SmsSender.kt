package com.kavach.caller.data.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends SMS, and says what happened.
 *
 * The previous implementation called `SmsManager.getDefault()` and swallowed
 * every failure, so a message with no SEND_SMS permission (or on the wrong SIM)
 * looked like it had been sent when nothing had. This returns a result the UI
 * can show instead.
 */
@Singleton
class SmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface Result {
        data object Sent : Result
        data class Failed(val reason: String) : Result
    }

    fun canSend(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Send [text] to [address].
     *
     * @param subscriptionId the SIM to send from, when the caller knows it.
     */
    fun send(address: String, text: String, subscriptionId: Int? = null): Result {
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
            if (parts.size <= 1) {
                manager.sendTextMessage(address, null, text, null, null)
            } else {
                manager.sendMultipartTextMessage(address, null, parts, null, null)
            }
            Result.Sent
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "The message could not be sent.")
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveManager(subscriptionId: Int?): SmsManager? = runCatching {
        if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            return@runCatching SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
        // Default SIM when we were not told which one to use.
        val subs = context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            ?.firstOrNull()
        if (subs != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SmsManager.getSmsManagerForSubscriptionId(subs.subscriptionId)
        } else {
            SmsManager.getDefault()
        }
    }.getOrNull()
}
