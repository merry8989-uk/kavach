package com.kavach.caller.data.remote

import android.content.Context
import com.kavach.caller.data.secure.SecretStore
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gives this install its own API token.
 *
 * The app used to send one bearer token that was identical on every device. An
 * APK is trivially decompilable, so that token was public in practice, and
 * because it was shared it could never be revoked without breaking everyone.
 *
 * So the shared token is now only a **bootstrap**: the user pastes it once, and
 * the app immediately exchanges it for a token belonging to this install. From
 * then on the bootstrap is not used for anything else, and this install can be
 * cut off on the server without touching anyone else.
 *
 * The install id is a random value generated here. It is not a device
 * identifier — nothing about the phone goes into it.
 */
@Singleton
class AppRegistration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** The token to send. Falls back to the bootstrap before registration. */
    fun token(): String? =
        secrets.get(SecretStore.INSTALL_TOKEN)?.takeIf { it.isNotBlank() }
            ?: settings.snapshot.backendToken.takeIf { it.isNotBlank() }

    fun isRegistered(): Boolean = !secrets.get(SecretStore.INSTALL_TOKEN).isNullOrBlank()

    /**
     * Register if we have not already.
     *
     * Safe to call on every start: with a token already stored it does nothing,
     * and with no server or no bootstrap token configured it returns quietly
     * rather than failing a launch.
     *
     * @return null on success or when there is nothing to do, otherwise why not.
     */
    suspend fun ensureRegistered(): String? = withContext(Dispatchers.IO) {
        if (isRegistered()) return@withContext null

        val base = settings.snapshot.backendBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext null

        val bootstrap = settings.snapshot.backendToken.trim()
        if (bootstrap.isBlank()) return@withContext null

        val body = buildJsonObject {
            put("install_id", JsonPrimitive(installId()))
            put("platform", JsonPrimitive("android"))
            put("app_version", JsonPrimitive(sessionVersion()))
        }

        val request = Request.Builder()
            .url("$base/v1/app/register")
            // Only the bootstrap token is accepted here, and only here.
            .header("Authorization", "Bearer $bootstrap")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val token = runCatching {
                            json.parseToJsonElement(text).jsonObjectToken()
                        }.getOrNull()
                        if (token.isNullOrBlank()) {
                            "Registration succeeded but returned no token."
                        } else {
                            secrets.put(SecretStore.INSTALL_TOKEN, token)
                            null
                        }
                    }
                    response.code == 429 ->
                        "This server is refusing new registrations right now. Try again later."
                    response.code == 403 ->
                        "The server rejected the bootstrap token. Check it in Settings → Server."
                    else ->
                        "Registration failed (${response.code})."
                }
            }
        }.getOrElse { throwable -> throwable.javaClass.simpleName }
    }

    /** Forget the install token, so the next call registers again. */
    fun forget() {
        secrets.put(SecretStore.INSTALL_TOKEN, null)
    }

    private fun jsonElementToken(text: String): String =
        Json.parseToJsonElement(text).jsonObjectToken()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectToken(): String =
        (this as? kotlinx.serialization.json.JsonObject)
            ?.get("token")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    /**
     * A random value, made once and kept.
     *
     * Stored in a plain preference because it is not a secret — it identifies the
     * install, not the person, and the server never sees anything else about the
     * device.
     */
    private fun installId(): String {
        val prefs = context.getSharedPreferences("kavach_install", Context.MODE_PRIVATE)
        prefs.getString("install_id", null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", generated).apply()
        return generated
    }

    private fun sessionVersion(): String =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
