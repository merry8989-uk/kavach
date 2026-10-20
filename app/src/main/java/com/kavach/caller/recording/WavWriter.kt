package com.kavach.caller.recording

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal 16-bit PCM WAV writer. The 44-byte header is written as a
 * placeholder on open and patched with the real sizes on close, because the
 * total length isn't known until recording stops.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) {
    private val out = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        out.setLength(0)
        out.write(ByteArray(HEADER_SIZE))
    }

    fun write(pcm: ByteArray, length: Int) {
        out.write(pcm, 0, length)
        dataBytes += length
    }

    fun close() {
        patchHeader()
        out.close()
    }

    private fun patchHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt((36 + dataBytes).toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())                 // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes.toInt())
        out.seek(0)
        out.write(buffer.array())
    }

    companion object {
        const val HEADER_SIZE = 44
    }
}
