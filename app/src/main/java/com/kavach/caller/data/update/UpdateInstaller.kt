package com.kavach.caller.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a release APK and hands it to the system package installer.
 *
 * Only URLs under Kavach's own GitHub release path are accepted, so a tampered
 * or spoofed API response can't push an arbitrary APK at the user.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val okHttp: OkHttpClient,
) {
    private val allowedPrefix = "https://github.com/${UpdateChecker.OWNER}/${UpdateChecker.REPO}/"

    suspend fun download(url: String, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith(allowedPrefix)) return@withContext null
            try {
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "kavach-update.apk")
                val request = Request.Builder().url(url).build()
                okHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var done = 0L
                            var read = input.read(buffer)
                            while (read != -1) {
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) onProgress(((done * 100) / total).toInt())
                                read = input.read(buffer)
                            }
                        }
                    }
                }
                if (out.length() > 0) out else null
            } catch (t: Throwable) {
                null
            }
        }

    /** Launch the system installer for a downloaded APK. */
    fun install(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        }
    }
}
