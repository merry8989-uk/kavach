package com.kavach.caller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.kavach.caller.ai.AssistantOrchestrator
import com.kavach.caller.ai.CloudAiClient
import com.kavach.caller.data.local.entity.RecordingEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.recording.AudioCaptureEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The AI call assistant's runtime (F10). When call screening routes a call to
 * the assistant, this foreground service:
 *
 *   1. answers the call,
 *   2. announces the assistant and that the call is recorded (consent),
 *   3. speaks the greeting,
 *   4. captures the call audio to a WAV,
 *   5. when the call ends, transcribes (cloud, if enabled), summarises, saves
 *      the transcript + recording, and posts the brief.
 *
 * Audio injection is a platform limitation — see [CallAssistantController].
 */
@AndroidEntryPoint
class CallAssistantService : Service() {

    @Inject lateinit var orchestrator: AssistantOrchestrator
    @Inject lateinit var controller: CallAssistantController
    @Inject lateinit var announcer: Announcer
    @Inject lateinit var callLog: CallLogRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifier: CallBriefNotifier
    @Inject lateinit var cloudAi: CloudAiClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val capture = AudioCaptureEngine()

    private var number: String = ""
    private var recordFile: File? = null
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
                startForegroundCompat()
                runAssistant()
            }
            ACTION_STOP -> finishAndStop()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "kavach_assistant"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(channelId, "AI assistant", NotificationManager.IMPORTANCE_LOW)
                )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kavach is handling this call")
            .setContentText("Answering, announcing and transcribing")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun runAssistant() {
        scope.launch {
            val decision = orchestrator.buildDecision(number)
            if (!decision.answer) {
                finishAndStop()
                return@launch
            }

            controller.setInCommunication()
            controller.setSpeakerphoneOn(true)
            val answered = controller.answerCall()
            delay(700) // let the call connect

            // Consent notice + greeting, spoken so the caller hears it.
            val prefix = if (answered) "" else "I was unable to answer automatically. "
            announcer.speak(
                "${prefix}This call is handled by an automated Kavach assistant and is being recorded. " +
                    decision.greeting
            )
            delay(4_000) // let the greeting play out

            // Capture the call audio.
            val dir = File(filesDir, "assistant").apply { mkdirs() }
            val file = File(dir, "call_${System.currentTimeMillis()}.wav")
            val source = capture.start(file)
            recordFile = if (source == AudioCaptureEngine.Source.NONE) null else file

            // Wait for the call to end (or a hard cap).
            var waited = 0L
            while (waited < MAX_CALL_MS) {
                delay(1_000)
                waited += 1_000
                if (!KavachInCallService.hasActiveCall()) break
            }
            finishAndStop()
        }
    }

    private fun finishAndStop() {
        if (finishing) return
        finishing = true
        scope.launch {
            val captured = capture.isRunning()
            capture.stop()
            val file = recordFile
            val hasAudio = file != null && file.exists() && file.length() > 44

            val recordingId = if (hasAudio) {
                callLog.saveRecording(
                    RecordingEntity(
                        number = number,
                        filePath = file!!.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        sizeBytes = file.length(),
                    )
                )
            } else null

            val transcript = transcribeIfPossible(file, hasAudio)
            val intent = orchestrator.onUtterance(number, transcript)
            val brief = orchestrator.finish(number, transcript, intent, file?.absolutePath)
            notifier.notifyBrief(brief)

            controller.setSpeakerphoneOn(false)
            controller.resetAudioMode()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    /** Cloud transcription, when the user enabled cloud AI. */
    private suspend fun transcribeIfPossible(file: File?, hasAudio: Boolean): String {
        if (!hasAudio || file == null) return "[no audio captured]"
        if (!settings.snapshot.cloudAiEnabled) {
            return "[audio captured locally — enable cloud AI to transcribe]"
        }
        return runCatching {
            val bytes = file.readBytes()
            val capped = if (bytes.size > MAX_UPLOAD_BYTES) bytes.copyOf(MAX_UPLOAD_BYTES) else bytes
            val encoded = Base64.encodeToString(capped, Base64.NO_WRAP)
            cloudAi.transcribe(encoded)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "[audio captured — transcription unavailable]"
    }

    override fun onDestroy() {
        capture.stop()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 4300
        private const val MAX_CALL_MS = 5 * 60 * 1000L
        private const val MAX_UPLOAD_BYTES = 3_000_000

        const val ACTION_START = "com.kavach.caller.assistant.START"
        const val ACTION_STOP = "com.kavach.caller.assistant.STOP"
        const val EXTRA_NUMBER = "number"

        fun start(context: Context, number: String) {
            val intent = Intent(context, CallAssistantService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NUMBER, number)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CallAssistantService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
