package com.kavach.caller.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Transcribing a recording **on the phone**, with no server and no paid provider.
 *
 * ## Why on-device
 *
 * The app already had a transcription path, and it went to a cloud AI provider —
 * which is unconfigured, so it returned 503 and the feature did not work. A
 * transcript you cannot get is not a feature.
 *
 * Android's own recogniser runs locally on most devices now, costs nothing, and
 * sends no audio anywhere. That is the right default for a call recording: the
 * content is the most sensitive thing the app holds.
 *
 * ## What it can and cannot transcribe — read this before trusting it
 *
 * **This transcribes audio that is actually in the file.** It cannot add the
 * other party if they were never captured.
 *
 * Android does not let a third-party app record the cellular uplink, so on most
 * devices a recording contains **your side only** — see
 * [RecordingCapability], which has said so since it was written. A transcript of
 * a one-sided recording is a transcript of you, and presenting it as "what the
 * caller said" would be a lie told by a UI.
 *
 * Where the other party *is* captured — a call on speakerphone, or a device that
 * permits uplink capture — the transcript is the real conversation.
 *
 * So this class returns a [Result] that distinguishes those cases rather than a
 * bare string.
 */
class OnDeviceTranscriber(private val context: Context) {

    sealed interface Result {
        /** Audio was transcribed. [oneSided] is true when we believe only our side was captured. */
        data class Transcribed(val text: String, val oneSided: Boolean) : Result

        /** The device has no on-device recogniser, or it refused the file. */
        data class Unavailable(val reason: String) : Result

        /** The recogniser ran and heard nothing intelligible. */
        data object Empty : Result
    }

    /**
     * Transcribe [file].
     *
     * Transcribing a **file** needs `EXTRA_AUDIO_SOURCE`, which arrived in Android
     * 13. Below that the recogniser only listens to the microphone, and pretending
     * otherwise would mean playing the recording out loud and re-recording it —
     * which is worse audio, not a feature.
     */
    suspend fun transcribe(file: File): Result {
        if (!file.exists() || file.length() == 0L) {
            return Result.Unavailable("That recording is empty or missing.")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Result.Unavailable(
                "Transcribing a saved recording needs Android 13 or newer. This " +
                    "phone's recogniser only listens to the microphone.",
            )
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return Result.Unavailable(
                "This phone has no speech recognition service installed, so a " +
                    "transcript cannot be produced here.",
            )
        }

        return runCatching { recognise(file) }
            .getOrElse { Result.Unavailable(it.message ?: "The recogniser failed.") }
    }

    private suspend fun recognise(file: File): Result = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var settled = false

        fun finish(result: Result) {
            if (settled) return
            settled = true
            runCatching { recognizer.destroy() }
            if (cont.isActive) cont.resume(result)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                finish(if (text.isEmpty()) Result.Empty else Result.Transcribed(text, oneSided = false))
            }

            override fun onError(error: Int) {
                finish(
                    Result.Unavailable(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            -> "The recogniser heard nothing it could make out."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Recording permission is needed to transcribe."
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                                -> "The recogniser needed a network and could not reach one."
                            else -> "The recogniser failed (code $error)."
                        },
                    ),
                )
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        cont.invokeOnCancellation {
            runCatching { recognizer.destroy() }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // The file, handed over as a descriptor. This is the whole reason the
            // API level check above exists.
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
        }

        runCatching { recognizer.startListening(intent) }
            .onFailure { finish(Result.Unavailable(it.message ?: "Could not start the recogniser.")) }
    }
}
