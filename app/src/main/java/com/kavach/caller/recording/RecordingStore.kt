package com.kavach.caller.recording

import android.content.Context
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.local.dao.RecordingDao
import com.kavach.caller.data.local.entity.RecordingEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the recording files: encrypt, record metadata, enforce the quota, play
 * back, delete, export.
 *
 * The important change from the previous version: `RecordingEntity.encrypted`
 * used to default to `true` while nothing was ever encrypted. The field is now
 * set from what actually happened.
 */
@Singleton
class RecordingStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: RecordingCrypto,
    private val dao: RecordingDao,
    private val capability: RecordingCapability,
    private val diagnostics: DiagnosticsRepository,
) {
    data class SaveResult(
        val entity: RecordingEntity,
        val encrypted: Boolean,
        val evicted: Int,
    )

    private val recordingsDir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Scratch space for the player, which needs a real path. */
    private val playbackDir: File get() = File(context.cacheDir, "playback").apply { mkdirs() }

    /** Where the recorder writes while the call is running. */
    val stagingDir: File get() = File(context.cacheDir, "recording-staging").apply { mkdirs() }

    /**
     * Take a finished recording, encrypt it, record it, and trim the quota.
     *
     * @return null when there was nothing usable, in which case the caller
     *   should say so rather than leaving an empty entry behind.
     */
    suspend fun finalize(
        plain: File,
        number: String,
        durationSec: Long,
    ): SaveResult? = withContext(Dispatchers.IO) {
        if (!plain.exists() || plain.length() == 0L) {
            runCatching { plain.delete() }
            return@withContext null
        }

        val target = File(recordingsDir, "${plain.nameWithoutExtension}.${RecordingCrypto.EXTENSION}")
        val encryptedBytes = crypto.encryptTo(plain, target)

        // Fall back to keeping the plaintext rather than losing the call, and
        // record that honestly.
        val finalFile: File
        val encrypted: Boolean
        if (encryptedBytes != null && encryptedBytes > 0) {
            finalFile = target
            encrypted = true
        } else {
            val fallback = File(recordingsDir, plain.name)
            runCatching { plain.copyTo(fallback, overwrite = true); plain.delete() }
            finalFile = fallback
            encrypted = false
        }

        val entity = RecordingEntity(
            number = number,
            filePath = finalFile.absolutePath,
            timestamp = System.currentTimeMillis(),
            durationSec = durationSec,
            sizeBytes = finalFile.length(),
            encrypted = encrypted,
        )
        val id = dao.insert(entity)
        val saved = entity.copy(id = id)

        diagnostics.dataQueryResult(
            source = "recording_saved",
            itemCount = 1,
            queryMs = null,
        )

        val evicted = enforceQuota()
        SaveResult(saved, encrypted, evicted)
    }

    /**
     * Keep the recordings directory inside its budget.
     *
     * Oldest first, so a device that records every call does not fill up and
     * start failing to write. Returns how many were removed.
     */
    suspend fun enforceQuota(): Int = withContext(Dispatchers.IO) {
        val all = runCatching { dao.allOnce() }.getOrDefault(emptyList())
            .sortedBy { it.timestamp }
        if (all.isEmpty()) return@withContext 0

        var totalBytes = all.sumOf { it.sizeBytes }
        var removed = 0
        val survivors = all.toMutableList()

        while (survivors.size > MAX_RECORDINGS || totalBytes > MAX_BYTES) {
            val oldest = survivors.removeAt(0)
            runCatching { File(oldest.filePath).delete() }
            runCatching { dao.deleteById(oldest.id) }
            totalBytes -= oldest.sizeBytes
            removed++
        }

        if (removed > 0) {
            diagnostics.errorStateShown(
                errorCode = "recording_quota_trimmed",
                screen = "RecordingsScreen",
                messageKey = "removed_$removed",
            )
        }
        removed
    }

    /**
     * A file the media player can actually open.
     *
     * Encrypted recordings are decrypted into the cache first. The caller is
     * responsible for calling [discardPlayback] afterwards.
     */
    suspend fun playbackFile(entity: RecordingEntity): File? = withContext(Dispatchers.IO) {
        val source = File(entity.filePath)
        if (!source.exists()) return@withContext null

        if (!entity.encrypted || !crypto.isEncrypted(source)) {
            return@withContext source
        }
        val temp = File(playbackDir, "${source.nameWithoutExtension}.m4a")
        if (crypto.decryptToTemp(source, temp)) temp else null
    }

    /** Remove a decrypted copy from the cache as soon as playback ends. */
    fun discardPlayback() {
        runCatching { playbackDir.listFiles()?.forEach { it.delete() } }
    }

    suspend fun delete(entity: RecordingEntity) = withContext(Dispatchers.IO) {
        runCatching { File(entity.filePath).delete() }
        runCatching { dao.deleteById(entity.id) }
        discardPlayback()
    }

    /** The user's own export: decrypted bytes written wherever they chose. */
    suspend fun export(entity: RecordingEntity, target: java.io.OutputStream): Boolean =
        withContext(Dispatchers.IO) {
            crypto.exportTo(File(entity.filePath), target)
        }

    suspend fun all(): List<RecordingEntity> = withContext(Dispatchers.IO) {
        migratePlaintextRecordings()
        runCatching { dao.allOnce() }.getOrDefault(emptyList())
    }

    /**
     * Encrypt recordings written by an older version.
     *
     * Before this change the files were plaintext while the row claimed
     * `encrypted = true`, so an upgrade has to make the data match the claim
     * rather than leaving the lie in place. Runs once; a file that is already
     * encrypted is skipped.
     *
     * @return how many were encrypted.
     */
    suspend fun migratePlaintextRecordings(): Int = withContext(Dispatchers.IO) {
        val rows = runCatching { dao.allOnce() }.getOrDefault(emptyList())
        var converted = 0

        rows.forEach { row ->
            val file = File(row.filePath)
            if (!file.exists() || crypto.isEncrypted(file)) return@forEach

            val target = File(recordingsDir, "${file.nameWithoutExtension}.${RecordingCrypto.EXTENSION}")
            val written = crypto.encryptTo(file, target)
            if (written != null && written > 0) {
                runCatching { dao.upsert(row.copy(filePath = target.absolutePath, sizeBytes = written, encrypted = true)) }
                converted++
            } else {
                // Encryption failed: record that honestly instead of claiming it.
                runCatching { dao.upsert(row.copy(encrypted = false)) }
            }
        }

        if (converted > 0) {
            diagnostics.dataQueryResult(source = "recordings_migrated", itemCount = converted)
        }
        converted
    }

    /** (bytes on disk, number of recordings, quota bytes) */
    fun usage(): Triple<Long, Int, Long> {
        val bytes = runCatching {
            recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        val count = runCatching { recordingsDir.listFiles()?.size ?: 0 }.getOrDefault(0)
        return Triple(bytes, count, MAX_BYTES)
    }

    /** Enough room to start a recording? */
    fun hasRoom(): Boolean =
        capability.freeSpaceMb() > RecordingCapability.MIN_FREE_SPACE_MB

    companion object {
        /** 500 MB or 200 calls, whichever comes first. */
        const val MAX_BYTES = 500L * 1024L * 1024L
        const val MAX_RECORDINGS = 200
    }
}
