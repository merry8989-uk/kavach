package com.kavach.caller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kavach.caller.domain.model.CallPhase
import com.kavach.caller.domain.model.CallSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.kavach.caller.R

/**
 * The ongoing call notification (F4).
 *
 * Shows who the call is with, whether they're saved, which SIM it is on, which
 * way round it is, and a live duration via the platform chronometer — the same
 * information a stock dialer puts in its notification.
 */
@Singleton
class CallNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun show(session: CallSession) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(session.title)
            .setContentText(subtitle(session))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

        contentIntent()?.let { builder.setContentIntent(it) }

        if (session.isConnected && session.connectedAtMillis != null) {
            // Platform-rendered, self-updating duration.
            builder.setUsesChronometer(true).setWhen(session.connectedAtMillis)
        } else {
            builder.setUsesChronometer(false).setShowWhen(false)
        }

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun clear() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun subtitle(session: CallSession): String {
        val parts = mutableListOf<String>()

        // Direction first — the "sign" the user asked for.
        parts += when (session.phase) {
            CallPhase.RINGING -> "Incoming call"
            CallPhase.DIALING -> "Outgoing call"
            else -> "${session.directionLabel} call"
        }
        // The verdict replaces "not in contacts" when there is one, because a
        // warning is more useful than a fact the user can already see.
        val verdict = session.verdict
        if (verdict != null && verdict.isWarning) {
            parts += verdict.headline
        } else if (!session.isKnownContact) {
            parts += "not in contacts"
        }
        session.simLabel?.let { parts += it }
        if (session.isMuted) parts += "muted"
        if (session.isSpeakerOn) parts += "speaker"
        return parts.joinToString(" · ")
    }

    private fun contentIntent(): PendingIntent? = runCatching {
        val intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                "Ongoing calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shows who you are talking to and for how long"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL = "kavach_calls"
        const val NOTIFICATION_ID = 4401
    }
}
