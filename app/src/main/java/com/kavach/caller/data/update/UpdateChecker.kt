package com.kavach.caller.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ReleaseDto(
    val tag_name: String,
    val body: String? = null,
    val html_url: String = "",
    val published_at: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String,
    val browser_download_url: String,
)

/**
 * Checks GitHub Releases for a newer Kavach build.
 *
 * Read-only, unauthenticated GET against the public releases API. Fails soft
 * (returns null) so a flaky network never blocks the app.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val okHttp: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val dto = json.decodeFromString<ReleaseDto>(body)
                val apk = dto.assets.firstOrNull { it.name.endsWith(".apk") }
                UpdateInfo(
                    versionName = dto.tag_name.removePrefix("v"),
                    notes = dto.body.orEmpty().trim(),
                    apkUrl = apk?.browser_download_url,
                    htmlUrl = dto.html_url,
                    publishedAt = dto.published_at.orEmpty(),
                )
            }
        }.getOrNull()
    }

    companion object {
        const val OWNER = "merry8989-uk"
        const val REPO = "kavach"

        /** True when [remote] is a strictly higher version than [current]. */
        fun isNewer(remote: String, current: String): Boolean {
            fun parts(v: String) = v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
            val a = parts(remote)
            val b = parts(current)
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
