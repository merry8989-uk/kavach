package com.kavach.caller.data.repo

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores contact photos inside Kavach's own storage.
 *
 * Why copy rather than keep the picked URI: a gallery pick returns a
 * short-lived permission, so a stored `content://` URI often stops resolving
 * after a reboot or a cache clear. Copying the bytes in means the photo keeps
 * working. A plain `https://` URL is stored as-is, because Coil can fetch it
 * and the user chose it deliberately.
 */
@Singleton
class ContactPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** A new file for the camera to write into. Returns `(file, uri)`. */
    fun newCaptureTarget(): Pair<File, Uri> {
        val file = File(directory, "capture-${UUID.randomUUID()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return file to uri
    }

    /** Copy a picked image into app storage and return its path. */
    suspend fun importFrom(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(directory, "photo-${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        }.getOrNull()
    }

    /** Delete a previously stored photo, if we own it. */
    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching {
            val file = File(path)
            if (file.parentFile?.name == DIR && file.exists()) file.delete()
        }
    }

    private companion object {
        const val DIR = "contact-photos"
    }
}
