package com.kavach.caller.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kavach.caller.ai.CallBrief
import com.kavach.caller.ai.CallIntent
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.kavach.caller.R

/** Posts the post-call brief (F10.7) as a notification. */
@Singleton
class CallBriefNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notifyBrief(brief: CallBrief) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "AI call briefs", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val urgent = brief.intent == CallIntent.EMERGENCY
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(
                if (urgent) "Urgent call handled by Kavach" else "Kavach handled a call"
            )
            .setContentText("${PhoneUtils.pretty(brief.number)} — ${brief.summary}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${brief.summary}\n\nIntent: ${brief.intent} · Risk: ${brief.risk}/100\n\n${brief.transcript}"
                )
            )
            .setPriority(
                if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIF_ID, notification) }
    }

    private companion object {
        const val CHANNEL = "kavach_briefs"
        const val NOTIF_ID = 4301
    }
}
