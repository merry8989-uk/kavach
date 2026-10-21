package com.kavach.caller.data.remote

import android.net.Uri
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the Kavach backend (F9.3 / F9.4 / F9.5 / F9.6).
 *
 * The server URL and token are **user-provided settings**, because Kavach does
 * not ship a hosted backend. Until they are filled in, [isConfigured] is false,
 * the remote-lookup feature reports itself as unavailable, and nothing here is
 * ever called — no silent failures, no invented names.
 *
 * Calls are bounded: this client uses a short-timeout copy of the shared OkHttp
 * client, so a slow server can never stall the dialer.
 */
@Singleton
class KavachApiClient @Inject constructor(
    okHttp: OkHttpClient,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsRepository,
) {
    private val http: OkHttpClient = okHttp.newBuilder()
        .callTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json".toMediaType()

    val isConfigured: Boolean
        get() = settings.snapshot.backendBaseUrl.isNotBlank()

    private fun baseUrl(): String? =
        settings.snapshot.backendBaseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }

    // ---------------- endpoints ----------------

    suspend fun lookup(number: String): LookupDto? {
        val url = baseUrl() ?: return null
        return get("$url/v1/lookup?number=${Uri.encode(number)}", LookupDto.serializer())
    }

    suspend fun reputation(number: String): ReputationDto? {
        val url = baseUrl() ?: return null
        return get("$url/v1/number-reputation?number=${Uri.encode(number)}", ReputationDto.serializer())
    }

    suspend fun searchBusinesses(
        query: String,
        category: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): BusinessSearchDto? {
        val url = baseUrl() ?: return null
        val path = buildString {
            append("$url/v1/businesses/search?q=").append(Uri.encode(query))
            append("&page=").append(page)
            append("&pageSize=").append(pageSize)
            if (!category.isNullOrBlank()) append("&category=").append(Uri.encode(category))
        }
        return get(path, BusinessSearchDto.serializer())
    }

    suspend fun reportSpam(number: String, category: String, note: String? = null): ReportResponseDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/reports",
            ReportRequestDto(number = number, category = category, note = note),
            ReportRequestDto.serializer(),
            ReportResponseDto.serializer(),
        )
    }

    suspend fun suggestName(number: String, name: String, evidence: String? = null): CorrectionResponseDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/identity-corrections",
            CorrectionRequestDto(number = number, suggestedName = name, evidence = evidence),
            CorrectionRequestDto.serializer(),
            CorrectionResponseDto.serializer(),
        )
    }

    // ---------------- plumbing ----------------

    private fun newRequest(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        settings.snapshot.backendToken
            .takeIf { it.isNotBlank() }
            ?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun <T> get(
        url: String,
        deserializer: DeserializationStrategy<T>,
    ): T? = execute(newRequest(url).get().build(), deserializer)

    private suspend fun <B, T> post(
        url: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
    ): T? {
        val payload = json.encodeToString(serializer, body)
        return execute(newRequest(url).post(payload.toRequestBody(jsonMedia)).build(), deserializer)
    }

    private suspend fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
    ): T? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 503 = the server is honest about an unconfigured provider.
                    diagnostics.warn("Remote", "${response.code} ${request.url.encodedPath}")
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                json.decodeFromString(deserializer, body)
            }
        }.getOrElse { throwable ->
            diagnostics.warn("Remote", "request failed: ${throwable.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_SECONDS = 4L
    }
}
