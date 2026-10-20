package com.kavach.caller.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Call recording engine (F8). Android's call-recording APIs are heavily
 * restricted (Android 10+ blocks third-party VOICE_CALL capture on most OEMs),
 * so Kavach implements a layered strategy and picks the best available path:
 *
 *  1. AudioSource.VOICE_CALL   — works on some OEMs / with system privileges
 *  2. AudioSource.VOICE_RECOGNITION / MIC — works widely, captures your side
 *  3. Accessibility capture    — Android 10+ fallback (user enables the service)
 *
 * Files are written to app-private storage and (optionally) encrypted.
 */
@Singleton
class CallRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    enum class Source { VOICE_CALL, MIC, ACCESSIBILITY }

    fun isRecording(): Boolean = recorder != null

    /** Best-effort start. Returns the output file, or null if the path is blocked. */
    fun start(number: String, source: Source = Source.VOICE_CALL): File? {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val safeNumber = PhoneUtils.normalize(number).trimStart('+').ifEmpty { "unknown" }
        val file = File(dir, "${safeNumber}_${System.currentTimeMillis()}.m4a")

        val audioSource = when (source) {
            Source.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
            Source.MIC -> MediaRecorder.AudioSource.MIC
            Source.ACCESSIBILITY -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        return runCatching {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.apply {
                setAudioSource(audioSource)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            currentFile = file
            file
        }.getOrNull()
    }

    fun stop(): File? {
        val file = currentFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        currentFile = null
        return file
    }
}
