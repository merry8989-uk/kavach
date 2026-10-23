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
import com.kavach.caller.data.secure.SecretStore

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
    private val secrets: com.kavach.caller.data.secure.SecretStore,
) {
    private val http: OkHttpClient = okHttp.newBuilder()
        .callTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json".toMediaType()

    /** Offline-only mode means we never touch the network, whatever else is set. */
    val isConfigured: Boolean
        get() = settings.snapshot.backendBaseUrl.isNotBlank() && !settings.snapshot.offlineOnly

    private fun baseUrl(): String? {
        // Offline-only short-circuits here, so no endpoint can bypass it.
        if (settings.snapshot.offlineOnly) return null
        return settings.snapshot.backendBaseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }
    }

    /**
     * Ask the server whether it is there, and say what it answered.
     *
     * Checks `/health/ready` rather than `/health`, because the second one only
     * proves the process is up. A server whose database is unreachable answers
     * liveness and fails every real request, which is exactly the state a user
     * would blame the app for.
     *
     * Returns a sentence for the user, never a bare boolean — "it did not work"
     * is not a diagnosable message.
     */
    suspend fun testConnection(url: String, token: String): String {
        val base = url.trim().trimEnd('/')
        // The rule lives in ServerUrlPolicy so it can be tested; see
        // ServerUrlPolicyTest for the cases that matter.
        (ServerUrlPolicy.check(base) as? ServerUrlPolicy.Verdict.Rejected)?.let {
            return it.reason
        }

        return runCatching {
            val request = Request.Builder()
                .url("$base/health/ready")
                .get()
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful ->
                        "Connected. The server is up and can reach its database."
                    response.code == 401 || response.code == 403 ->
                        "The server answered, but refused the token. Check the API token."
                    response.code == 404 ->
                        "Something answered at that address, but it is not a Kavach server."
                    else ->
                        "The server answered with HTTP ${response.code}."
                }
            }
        }.getOrElse { error ->
            "Could not reach the server: ${error.javaClass.simpleName}. " +
                "Check the address, and that the phone is on the same network."
        }
    }


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

    /**
     * Tell the server a number has been labelled wrongly.
     *
     * Nothing changes immediately — a person reviews it, and an approved appeal
     * clears the number's score. The response says so in as many words rather
     * than implying an instant fix.
     */
    suspend fun submitAppeal(number: String, statement: String? = null): AppealResponseDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/appeals",
            AppealRequestDto(number = number, statement = statement),
            AppealRequestDto.serializer(),
            AppealResponseDto.serializer(),
        )
    }

    /**
     * Claim a listing for a business.
     *
     * Goes to the moderation queue rather than publishing itself, and the
     * response says so. A business is a claim about a person's phone number, and
     * the only thing between a real clinic and someone impersonating one is a
     * person reading the evidence.
     */
    suspend fun claimBusiness(
        number: String,
        businessName: String,
        category: String?,
        evidence: String,
    ): BusinessClaimResponseDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/businesses/claim",
            BusinessClaimRequestDto(
                number = number,
                businessName = businessName,
                category = category,
                evidence = evidence,
            ),
            BusinessClaimRequestDto.serializer(),
            BusinessClaimResponseDto.serializer(),
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

    /**
     * Two kinds of auth, and they mean different things:
     *
     *  - **this install's own token** for lookup, reports and telemetry. It
     *    proves *what* is calling, and the server can revoke one install without
     *    touching anybody else.
     *  - **a per-user JWT** for anything acting on the user's own record —
     *    profile, consent, deletion. It proves *who*.
     *
     */
    private fun newRequest(url: String, userToken: String? = null): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        val bearer = userToken?.takeIf { it.isNotBlank() }
            ?: secrets.get(SecretStore.SERVER_TOKEN)?.takeIf { it.isNotBlank() }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun <T> get(
        url: String,
        deserializer: DeserializationStrategy<T>,
        userToken: String? = null,
    ): T? = execute(newRequest(url, userToken).get().build(), deserializer)

    private suspend fun <B, T> post(
        url: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
        userToken: String? = null,
    ): T? {
        val payload = json.encodeToString(serializer, body)
        return execute(
            newRequest(url, userToken).post(payload.toRequestBody(jsonMedia)).build(),
            deserializer,
        )
    }

    // ---------------- consent-based account (plan sections 4.1, 4.5) ----------------

    /** Step 1 of sign-in. Returns null when no server is configured. */
    suspend fun requestOtp(phone: String): OtpRequestResponseDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/auth/otp/request",
            OtpRequestBodyDto(phone = phone),
            OtpRequestBodyDto.serializer(),
            OtpRequestResponseDto.serializer(),
        )
    }

    /** Step 2. The returned token authenticates every `/v1/me` call. */
    suspend fun verifyOtp(requestId: String, otp: String): TokenDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/auth/otp/verify",
            OtpVerifyBodyDto(request_id = requestId, otp = otp),
            OtpVerifyBodyDto.serializer(),
            TokenDto.serializer(),
        )
    }

    suspend fun profile(userToken: String): ProfileDto? {
        val url = baseUrl() ?: return null
        return get("$url/v1/me", ProfileDto.serializer(), userToken)
    }

    suspend fun updateProfile(
        userToken: String,
        displayName: String?,
        publishName: Boolean,
        allowSpamReports: Boolean? = null,
    ): ProfileDto? {
        val url = baseUrl() ?: return null
        return put(
            "$url/v1/me/profile",
            ProfileUpdateDto(
                display_name = displayName,
                publish_name = publishName,
                allow_spam_reports = allowSpamReports,
            ),
            ProfileUpdateDto.serializer(),
            ProfileDto.serializer(),
            userToken,
        )
    }

    suspend fun consentHistory(userToken: String): List<ConsentEventDto>? {
        val url = baseUrl() ?: return null
        return get(
            "$url/v1/me/consent",
            kotlinx.serialization.builtins.ListSerializer(ConsentEventDto.serializer()),
            userToken,
        )
    }

    suspend fun deleteAccount(userToken: String): Boolean {
        val url = baseUrl() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(newRequest("$url/v1/me", userToken).delete().build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    /** A spam report from a verified user, weighted by their trust score. */
    suspend fun reportAsUser(
        userToken: String,
        number: String,
        category: String,
        note: String? = null,
    ): ReportResponseDto? {
        val url = baseUrl() ?: return null
        return post(
            "$url/v1/me/reports",
            ReportRequestDto(number = number, category = category, note = note),
            ReportRequestDto.serializer(),
            ReportResponseDto.serializer(),
            userToken,
        )
    }

    /** Suggest a name. Stored for review — never published as somebody's name. */
    suspend fun contributeName(
        userToken: String,
        number: String,
        name: String,
        evidence: String? = null,
    ): Boolean {
        val url = baseUrl() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = json.encodeToString(
                    ContributeDto.serializer(),
                    ContributeDto(number = number, name = name, evidence = evidence),
                )
                http.newCall(
                    newRequest("$url/v1/me/contributions", userToken)
                        .post(payload.toRequestBody(jsonMedia))
                        .build(),
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    private suspend fun <B, T> put(
        url: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
        userToken: String? = null,
    ): T? {
        val payload = json.encodeToString(serializer, body)
        return execute(
            newRequest(url, userToken).put(payload.toRequestBody(jsonMedia)).build(),
            deserializer,
        )
    }

    private suspend fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
    ): T? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        runCatching {
            http.newCall(request).execute().use { response ->
                diagnostics.apiRequest(
                    endpoint = request.url.encodedPath,
                    method = request.method,
                    statusCode = response.code,
                    latencyMs = System.currentTimeMillis() - started,
                )
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString(deserializer, body)
            }
        }.getOrElse { throwable ->
            diagnostics.apiRequest(
                endpoint = request.url.encodedPath,
                method = request.method,
                statusCode = 0,
                latencyMs = System.currentTimeMillis() - started,
                retryCount = 0,
            )
            null
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_SECONDS = 4L
    }
}
