package com.kavach.caller.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures call audio to a WAV file.
 *
 * Android blocks third-party uplink/downlink capture on Android 10+, so we try
 * the best available source in order and report which one actually worked.
 * Capturing the *caller's* audio reliably requires the default-dialer role;
 * otherwise we fall back to the microphone, which hears whatever the speaker
 * plays.
 */
class AudioCaptureEngine {

    enum class Source { VOICE_CALL, VOICE_RECOGNITION, MIC, NONE }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    var activeSource: Source = Source.NONE
        private set

    /** Try [preferred] first, then fall back. Returns the source that worked. */
    fun start(file: File, preferred: Source = Source.VOICE_CALL): Source {
        val candidates = when (preferred) {
            Source.VOICE_CALL -> listOf(Source.VOICE_CALL, Source.VOICE_RECOGNITION, Source.MIC)
            else -> listOf(preferred, Source.VOICE_RECOGNITION, Source.MIC)
        }
        for (candidate in candidates) {
            if (tryStart(file, candidate)) return candidate
        }
        return Source.NONE
    }

    private fun sourceId(source: Source): Int = when (source) {
        Source.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
        Source.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        Source.MIC -> MediaRecorder.AudioSource.MIC
        Source.NONE -> MediaRecorder.AudioSource.DEFAULT
    }

    @SuppressLint("MissingPermission")
    private fun tryStart(file: File, source: Source): Boolean {
        val sampleRate = 16_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) return false
        return try {
            val recorder = AudioRecord(sourceId(source), sampleRate, channel, encoding, minBuf * 2)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            recorder.startRecording()
            val writer = WavWriter(file, sampleRate)
            val buffer = ByteArray(minBuf)
            running.set(true)
            thread = Thread {
                try {
                    while (running.get()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) writer.write(buffer, read)
                    }
                } finally {
                    writer.close()
                }
            }.also { it.start() }
            record = recorder
            activeSource = source
            true
        } catch (t: Throwable) {
            runCatching { record?.release() }
            record = null
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { thread?.join(1_500) }
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    fun isRunning(): Boolean = running.get()
}
