package com.kavach.caller.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Missed-call reminder (F6.x).
 *
 * The system posts its own missed-call notification, but it is bare. This one
 * carries the resolved contact name, the formatted number, and a one-tap
 * call-back action.
 */
@Singleton
class MissedCallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyMissed(displayName: String?, number: String) {
        if (number.isBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val title = displayName ?: PhoneUtils.pretty(number)
        val text = if (displayName != null) {
            "Missed call · ${PhoneUtils.pretty(number)}"
        } else {
            "Missed call · not in your contacts"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "Call back", callBackIntent(number))
            .setContentIntent(openAppIntent())

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun callBackIntent(number: String): PendingIntent? = runCatching {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${PhoneUtils.toE164(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun openAppIntent(): PendingIntent? = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Missed calls",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Reminders about calls you missed" },
            )
        }
    }

    private companion object {
        const val CHANNEL = "kavach_missed"
        const val NOTIFICATION_ID = 4402
    }
}
