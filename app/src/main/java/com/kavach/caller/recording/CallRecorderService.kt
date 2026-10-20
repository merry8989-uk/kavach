package com.kavach.caller.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that keeps recording + AI capture alive while a call is
 * in progress. The persistent notification doubles as the consent notice
 * (required so the caller/user can see recording is active).
 */
@AndroidEntryPoint
class CallRecorderService : Service() {

    @Inject lateinit var recorder: CallRecorder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
                startForeground(NOTIF_ID, buildNotification(number))
                recorder.start(number)
            }
            ACTION_STOP -> {
                recorder.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(number: String): Notification {
        val channelId = "kavach_recording"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Call recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kavach is recording this call")
            .setContentText("Call with ${number.ifEmpty { "unknown number" }} — tap to stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4201
        const val ACTION_START = "com.kavach.caller.recording.START"
        const val ACTION_STOP = "com.kavach.caller.recording.STOP"
        const val EXTRA_NUMBER = "number"

        fun start(context: Context, number: String) {
            val i = Intent(context, CallRecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NUMBER, number)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CallRecorderService::class.java).apply { action = ACTION_STOP })
        }
    }
}
