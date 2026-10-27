package com.kavach.caller.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kavach.caller.MainActivity
import com.kavach.caller.R
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.domain.DefaultAppStatus
import com.kavach.caller.domain.DefaultRoleNudge
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The "set Kavach as default" reminder.
 *
 * Runs daily and posts a notification only when the rules say it is time — see
 * [DefaultRoleNudge]. Daily rather than every two days because WorkManager's
 * periodic intervals drift; a daily check against a timestamp that says "not
 * before Tuesday" gives the right answer even if the check itself is late.
 *
 * **It never asks twice for the same window.** Posting sets the next timestamp,
 * so a worker that runs late, or twice, cannot produce two notifications.
 */
@HiltWorker
class DefaultNudgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val snapshot = settings.settings.first()
        val isDefault = DefaultAppStatus.isDefault(applicationContext)
        val now = System.currentTimeMillis()

        if (!DefaultRoleNudge.shouldAsk(isDefault, snapshot.defaultNudgeAt, now)) {
            // Either it is already the default, or it is not time. Both mean there
            // is nothing to do, and neither is a failure.
            return Result.success()
        }

        runCatching { notify() }

        // Schedule the next window now rather than after the user acts: a
        // notification that is ignored should still not reappear tomorrow.
        settings.setDefaultNudgeAt(DefaultRoleNudge.nextAskAt(now))
        return Result.success()
    }

    private fun notify() {
        val context = applicationContext
        ensureChannel(context)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val missing = DefaultAppStatus.missing(context)
        val what = missing.joinToString(" and ") { it.label }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Finish setting up Kavach")
            .setContentText("$what still needs to be Kavach. Tap to set it.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$what still needs to be Kavach. Until it is, the in-call " +
                        "screen, caller identification and call recording cannot work.",
                ),
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // The permission is checked here rather than assumed: on Android 13+ a
        // notification without it throws, and a worker that crashes is worse than
        // one that stays quiet.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Setup reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "A reminder to finish making Kavach your default dialer"
            },
        )
    }

    companion object {
        const val CHANNEL = "kavach_setup"
        const val NOTIFICATION_ID = 4210
        const val UNIQUE_NAME = "kavach_default_nudge"
    }
}
