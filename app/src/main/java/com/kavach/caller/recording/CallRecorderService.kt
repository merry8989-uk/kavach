package com.kavach.caller.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kavach.caller.MainActivity
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps recording alive during a call.
 *
 * Two spec requirements are enforced here rather than assumed:
 *
 *  1. **Never record silently.** The persistent notification is the visible
 *     indicator, and recording does not start at all until the user has given
 *     explicit consent in the app.
 *  2. **Report what was actually captured.** Android usually gives an ordinary
 *     app only the microphone, so the result records which source worked
 *     instead of implying both sides were captured.
 */
@AndroidEntryPoint
class CallRecorderService : Service() {

    @Inject lateinit var recorder: CallRecorder
    @Inject lateinit var store: RecordingStore
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var capability: RecordingCapability
    @Inject lateinit var diagnostics: DiagnosticsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: ""

        // Consent first. A recording that starts before the user has agreed is
        // exactly the behaviour the spec forbids.
        if (!settings.snapshot.recordingConsentGiven) {
            showConsentPrompt()
            stopSelf()
            return
        }

        if (!store.hasRoom()) {
            showSimpleNotification(
                title = "Not enough space to record",
                text = "Free up some storage, then try again.",
            )
            stopSelf()
            return
        }

        startForeground(NOTIF_ID, buildNotification(number))
        val file = recorder.start(number)
        if (file == null) {
            // No audio path on this device. Say so rather than pretending.
            showSimpleNotification(
                title = "This device would not allow recording",
                text = capability.assess().headline,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startedAt = System.currentTimeMillis()
    }

    private fun handleStop() {
        val file = recorder.stop()
        val number = file?.nameWithoutExtension?.substringBefore('_') ?: "unknown"
        val seconds = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0L

        if (file != null) {
            scope.launch {
                val result = store.finalize(file, number, seconds)
                diagnostics.dataQueryResult(
                    source = if (result?.encrypted == true) "recording_encrypted" else "recording_plain",
                    itemCount = if (result == null) 0 else 1,
                )
            }
        }
        startedAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(number: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Recording this call")
            .setContentText("${number.ifEmpty { "Unknown number" }} — tap to open Kavach")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showConsentPrompt() {
        ensureChannel()
        showSimpleNotification(
            title = "Recording needs your consent",
            text = "Open Kavach → Recordings and turn recording on. Kavach will not record " +
                "without your agreement.",
        )
    }

    private fun showSimpleNotification(title: String, text: String) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Call recording", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while Kavach is recording a call" },
            )
        }
    }

    companion object {
        private const val NOTIF_ID = 4201
        private const val CHANNEL = "kavach_recording"

        const val ACTION_START = "com.kavach.caller.RECORD_START"
        const val ACTION_STOP = "com.kavach.caller.RECORD_STOP"
        const val EXTRA_NUMBER = "number"

        fun start(context: Context, number: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, CallRecorderService::class.java).apply {
                        action = ACTION_START
                        putExtra(EXTRA_NUMBER, number)
                    },
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CallRecorderService::class.java).apply { action = ACTION_STOP },
                )
            }
        }
    }
}
