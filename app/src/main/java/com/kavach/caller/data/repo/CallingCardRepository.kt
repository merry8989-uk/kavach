package com.kavach.caller.data.repo

import android.content.Context
import android.net.Uri
import com.kavach.caller.data.local.dao.CallingCardDao
import com.kavach.caller.data.local.entity.CallingCardEntity
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a call from one person looks.
 *
 * **The image is copied, not referenced.** A photo picker hands back a URI whose
 * read grant lasts as long as the process — sometimes less. Storing that URI
 * directly gives a card that works perfectly until the next morning, then shows a
 * grey rectangle, and the user has no way to tell that anything went wrong. So
 * the bytes are copied into the app's own storage and the card points at that.
 *
 * The copy is bounded. A 12-megapixel photo is 4 MB of pixels for something that
 * is drawn full-screen behind a name; keeping the original would mean a card per
 * contact quietly filling the device.
 */
@Singleton
class CallingCardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CallingCardDao,
) {

    private val dir: File get() = File(context.filesDir, "calling-cards").apply { mkdirs() }

    suspend fun forNumber(number: String): CallingCardEntity? = withContext(Dispatchers.IO) {
        runCatching { dao.forNumber(PhoneUtils.toE164(number)) }
            .getOrNull()
            ?.takeIf { it.enabled }
    }

    /** The card whatever its enabled state — for the editor. */
    suspend fun rawForNumber(number: String): CallingCardEntity? = withContext(Dispatchers.IO) {
        runCatching { dao.forNumber(PhoneUtils.toE164(number)) }.getOrNull()
    }

    suspend fun all(): List<CallingCardEntity> = withContext(Dispatchers.IO) {
        runCatching { dao.all() }.getOrDefault(emptyList())
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        runCatching { dao.count() }.getOrDefault(0)
    }

    /**
     * Save a card, copying the image in if one was chosen.
     *
     * @param pickedImage a URI from the picker, or null to leave the current
     *   image alone. Passing [clearImage] removes it.
     */
    suspend fun save(
        number: String,
        pickedImage: Uri? = null,
        clearImage: Boolean = false,
        headline: String? = null,
        subtitle: String? = null,
        accentColor: Int? = null,
        dimBackground: Boolean = true,
        enabled: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = PhoneUtils.toE164(number)
        val existing = runCatching { dao.forNumber(key) }.getOrNull()

        val image = when {
            clearImage -> {
                existing?.backgroundUri?.let { deleteFile(it) }
                null
            }
            pickedImage != null -> copyIn(pickedImage, key) ?: existing?.backgroundUri
            else -> existing?.backgroundUri
        }

        runCatching {
            dao.upsert(
                CallingCardEntity(
                    id = existing?.id ?: 0,
                    contactKey = key,
                    backgroundUri = image,
                    headline = headline?.trim()?.takeIf { it.isNotEmpty() },
                    subtitle = subtitle?.trim()?.takeIf { it.isNotEmpty() },
                    accentColor = accentColor,
                    dimBackground = dimBackground,
                    enabled = enabled,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.isSuccess
    }

    suspend fun setEnabled(number: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching { dao.setEnabled(PhoneUtils.toE164(number), enabled) }
    }

    suspend fun delete(number: String) = withContext(Dispatchers.IO) {
        val key = PhoneUtils.toE164(number)
        runCatching { dao.forNumber(key) }.getOrNull()?.backgroundUri?.let { deleteFile(it) }
        runCatching { dao.deleteForNumber(key) }
    }

    /**
     * Copy the picked image into app storage and return its file URI.
     *
     * Returns null on failure, and the caller keeps whatever image it had. A
     * failed copy must not blank an existing card.
     */
    private fun copyIn(source: Uri, key: String): String? = runCatching {
        val name = "card-" + key.replace(Regex("[^0-9]"), "").takeLast(12) + ".jpg"
        val target = File(dir, name)
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
        } ?: return null
        target.takeIf { it.exists() && it.length() > 0 }?.toURI().toString()
    }.getOrNull()

    private fun deleteFile(uriString: String) = runCatching {
        if (uriString.startsWith("file:")) File(Uri.parse(uriString).path ?: return@runCatching).delete()
    }

    /** Everything a card is using, for the "how much space" line in the editor. */
    suspend fun bytesUsed(): Long = withContext(Dispatchers.IO) {
        runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }
}
