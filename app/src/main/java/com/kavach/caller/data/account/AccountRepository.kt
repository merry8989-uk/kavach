package com.kavach.caller.data.account

import com.kavach.caller.data.remote.ConsentEventDto
import com.kavach.caller.data.remote.KavachApiClient
import com.kavach.caller.data.remote.ProfileDto
import com.kavach.caller.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own account: verification, profile and consent
 * (docs/CALLER-ID-PLAN.md, sections 4.1 and 4.5).
 *
 * Everything here needs a Kavach server. When none is configured the whole
 * feature is unavailable and the UI says so rather than failing silently.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val api: KavachApiClient,
    private val settings: SettingsRepository,
) {
    val isConfigured: Boolean get() = api.isConfigured

    val isSignedIn: Boolean get() = settings.snapshot.accountToken.isNotBlank()

    val displayName: String get() = settings.snapshot.accountDisplayName

    val publishName: Boolean get() = settings.snapshot.accountPublishName

    private val token: String get() = settings.snapshot.accountToken

    /**
     * Step 1. Returns the request id, and the code too in local dev builds.
     *
     * **Each failure says what actually happened.** This used to report "No
     * Kavach server is configured" for every one of them, including a server that
     * was configured and reachable — which sends the user to fix a setting that
     * was already right.
     */
    suspend fun requestOtp(phone: String): Result<OtpChallenge> = runCatching {
        when (val result = api.requestOtp(phone)) {
            is KavachApiClient.OtpResult.Sent ->
                OtpChallenge(
                    requestId = result.response.request_id,
                    debugCode = result.response.debug_code,
                )
            KavachApiClient.OtpResult.NoServer -> error(
                "No server is set. Add its URL under Settings, Self-hosted lookup.",
            )
            KavachApiClient.OtpResult.NotSupported -> error(
                "This server does not offer sign-in. Verifying a number needs an SMS " +
                    "gateway on the server, and the one you are pointing at has none.",
            )
            KavachApiClient.OtpResult.NoGateway -> error(
                "The server is reachable but has no SMS gateway configured, so it " +
                    "cannot send a code. This is a server setting, not something to " +
                    "fix on the phone.",
            )
            is KavachApiClient.OtpResult.Failed -> error(
                "The server answered HTTP ${result.code}.",
            )
        }
    }

    /** Step 2. Stores the JWT so later calls can act on this user's record. */
    suspend fun verifyOtp(requestId: String, code: String): Result<Boolean> = runCatching {
        val token = api.verifyOtp(requestId, code)
            ?: error("That code could not be verified.")
        val profile = api.profile(token.access_token)
        settings.setAccount(
            token = token.access_token,
            userId = token.user_id,
            displayName = profile?.display_name.orEmpty(),
            publishName = profile?.publish_name ?: false,
        )
        token.is_new
    }

    /** Re-read the profile from the server, refreshing the local mirror. */
    suspend fun refreshProfile(): ProfileDto? {
        if (!isSignedIn) return null
        val profile = api.profile(token) ?: return null
        settings.setAccount(
            token = token,
            userId = profile.user_id,
            displayName = profile.display_name.orEmpty(),
            publishName = profile.publish_name,
        )
        return profile
    }

    /**
     * Save the display name and the consent to publish it.
     *
     * Publishing requires a name; withdrawing is always allowed and takes effect
     * immediately on the server.
     */
    suspend fun saveProfile(name: String, publish: Boolean): Result<ProfileDto> = runCatching {
        val profile = api.updateProfile(token, name.ifBlank { null }, publish)
            ?: error("Could not reach the server.")
        settings.setAccount(
            token = token,
            userId = profile.user_id,
            displayName = profile.display_name.orEmpty(),
            publishName = profile.publish_name,
        )
        profile
    }

    suspend fun consentHistory(): List<ConsentEventDto> =
        runCatching { api.consentHistory(token) ?: emptyList() }.getOrDefault(emptyList())

    /** Report a number. Weighted by the server using this account's trust. */
    suspend fun reportSpam(number: String, category: String, note: String? = null): Result<String> =
        runCatching {
            val response = api.reportAsUser(token, number, category, note)
                ?: error("Could not reach the server.")
            response.detail ?: if (response.accepted) "Report recorded." else "Already reported."
        }

    /**
     * Suggest a name for a number. This never publishes anything: the server
     * holds it for review, and only the number's own owner can name it.
     */
    suspend fun contributeName(number: String, name: String): Result<String> = runCatching {
        val ok = api.contributeName(token, number, name)
        if (!ok) error("Could not reach the server.")
        "Thanks — held for review. Only the number's owner can name it."
    }

    /** Erase the account. The number stops resolving to a name. */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        if (!api.deleteAccount(token)) error("Could not reach the server.")
        signOut()
    }

    suspend fun signOut() {
        settings.setAccount("", "", "", false)
    }
}

data class OtpChallenge(val requestId: String, val debugCode: String?)
