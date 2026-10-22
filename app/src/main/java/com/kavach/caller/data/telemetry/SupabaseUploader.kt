package com.kavach.caller.data.telemetry

import com.kavach.caller.data.diagnostics.LogEventCodec
import com.kavach.caller.data.diagnostics.SessionContext
import com.kavach.caller.data.secure.SecretStore
import com.kavach.caller.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes diagnostics events straight into Supabase.
 *
 * Why this exists alongside the other two destinations: Supabase is managed
 * Postgres, so this path needs **no server to run at all**. The app talks to
 * PostgREST and the rows land in a real SQL table you can query, chart and join
 * — which is what you want at a hundred installs.
 *
 * How it stays safe with a public key:
 *
 *  - The app ships the **anon** key, which is designed to be public.
 *  - The table has row-level security with an insert-only policy for `anon` and
 *    **no select policy at all**, so that key cannot read a single row back.
 *  - Everything that protects the data lives in `supabase/schema.sql`, not here.
 *    See that file for the policies and the reasoning.
 *
 * The consent rules are unchanged from every other destination: off by default,
 * queued locally first, and nothing is deleted from the queue until Supabase has
 * accepted it.
 */
@Singleton
class SupabaseUploader @Inject constructor(
    private val settings: SettingsRepository,
    private val queue: UploadQueue,
    private val secrets: SecretStore,
    private val session: SessionContext,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Status(
        val enabled: Boolean,
        val hasKey: Boolean,
        val pending: Int,
        val sent: Long,
        val lastError: String?,
    )

    suspend fun status(): Status = Status(
        enabled = settings.snapshot.supabaseUploadEnabled,
        hasKey = !anonKey().isNullOrBlank() && settings.snapshot.supabaseUrl.isNotBlank(),
        pending = pendingCount(),
        sent = settings.snapshot.uploadSyncedTotal,
        lastError = settings.snapshot.uploadLastError,
    )

    suspend fun pendingCount(): Int = queue.count()

    private fun anonKey(): String? = secrets.get(SecretStore.SUPABASE_ANON_KEY)

    fun isConfigured(): Boolean =
        settings.snapshot.supabaseUploadEnabled &&
            settings.snapshot.supabaseUrl.isNotBlank() &&
            !anonKey().isNullOrBlank()

    /**
     * Send queued events.
     *
     * @return (sent, failure) — a non-null failure means the run stopped and the
     *   events are still queued.
     */
    suspend fun flush(limit: Int = MAX_PER_FLUSH): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            if (!isConfigured()) return@withContext 0 to null

            val lines = queue.lines()
            if (lines.isEmpty()) return@withContext 0 to null

            val batch = lines.take(limit)
            val rest = lines.drop(batch.size).toMutableList()

            val rows = batch.mapNotNull { line ->
                LogEventCodec.decode(line)?.let { event -> rowFor(event) }
            }
            if (rows.isEmpty()) {
                queue.keep(rest)
                return@withContext 0 to null
            }

            val result = post(JsonArray(rows))
            when (result) {
                is Result.Sent -> {
                    queue.keep(rest)
                    settings.setUploadLastSyncAt(System.currentTimeMillis())
                    settings.setUploadSyncedTotal(settings.snapshot.uploadSyncedTotal + rows.size)
                    settings.setUploadLastError(null)
                    rows.size to null
                }
                is Result.Failed -> {
                    settings.setUploadLastError(result.reason)
                    0 to result.reason
                }
            }
        }

    /**
     * Send one synthetic row and report the real answer.
     *
     * An earlier version of the upload path could not be debugged because a
     * rejected row was dropped without a word. This does the opposite: one
     * button, and the destination's own status and message back.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val key = anonKey()
        if (key.isNullOrBlank()) return@withContext "No Supabase anon key saved yet."
        val base = settings.snapshot.supabaseUrl.trim()
        if (base.isBlank()) return@withContext "No Supabase project URL set yet."

        val probe = buildJsonObject {
            put("user_code", JsonPrimitive(session.userCode))
            put("event_name", JsonPrimitive("session_start"))
            put("schema_version", JsonPrimitive("1.0.0"))
            put("event_timestamp", JsonPrimitive(java.time.Instant.now().toString()))
            put("session_id", JsonPrimitive(session.sessionId))
            put("level", JsonPrimitive("info"))
            put("app_version", JsonPrimitive(session.appVersion))
            put("os", JsonPrimitive(session.os))
            put("device_model", JsonPrimitive(session.deviceModel))
            put("platform", JsonPrimitive("android"))
            put("screen_or_route", JsonPrimitive("settings"))
            put("release_channel", JsonPrimitive("production"))
            put("properties", buildJsonObject { put("start_type", JsonPrimitive("cold")) })
        }

        when (val result = post(JsonArray(listOf(probe)))) {
            is Result.Sent ->
                "Connected. Supabase accepted a test row. The table exists and the anon key " +
                    "may insert."
            is Result.Failed -> result.reason
        }
    }

    private sealed interface Result {
        data object Sent : Result
        data class Failed(val reason: String) : Result
    }

    private fun post(payload: JsonArray): Result {
        val base = settings.snapshot.supabaseUrl.trim().trimEnd('/')
        val key = anonKey() ?: return Result.Failed("No Supabase key saved.")

        val request = Request.Builder()
            .url("$base/rest/v1/telemetry_events")
            // The anon key is public by design; the table's policies are what
            // actually protect the data.
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            // Do not ask PostgREST to echo the rows back. Besides being wasted
            // bytes, the select policy is deliberately absent, so asking would
            // fail on a policy that is working correctly.
            .header("Prefer", "return=minimal")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> Result.Sent
                    response.code == 401 || response.code == 403 ->
                        Result.Failed(
                            "Supabase refused the key (${response.code}). Check the anon key " +
                                "and that the insert policy exists.",
                        )
                    response.code == 404 ->
                        Result.Failed(
                            "Table not found (404). Run supabase/schema.sql in the SQL editor.",
                        )
                    else -> Result.Failed(
                        "Supabase returned ${response.code}: " +
                            response.body?.string()?.take(180).orEmpty(),
                    )
                }
            }
        }.getOrElse { throwable -> Result.Failed(throwable.javaClass.simpleName) }
    }

    /** Map one schema event onto the table's columns. */
    private fun rowFor(event: com.kavach.caller.data.diagnostics.LogEvent): JsonObject {
        // Everything that is not a named column belongs in `properties`.
        val reserved = setOf(
            "event_name", "schema_version", "timestamp", "session_id", "attempt_id",
            "user_code", "platform", "app_version", "os", "device_model",
            "network_type", "screen_or_route", "log_level", "release_channel",
        )
        val extra = event.properties.filterKeys { it !in reserved }

        return buildJsonObject {
            put("user_code", JsonPrimitive(session.userCode))
            put("event_name", JsonPrimitive(event.eventName))
            put("schema_version", JsonPrimitive("1.0.0"))
            put("event_timestamp", JsonPrimitive(event.iso8601()))
            put("session_id", JsonPrimitive(event.sessionId))
            event.attemptId?.let { put("attempt_id", JsonPrimitive(it)) }
            put("level", JsonPrimitive(event.logLevel.name.lowercase()))
            put("app_version", JsonPrimitive(session.appVersion))
            put("os", JsonPrimitive(session.os))
            put("device_model", JsonPrimitive(session.deviceModel))
            put("platform", JsonPrimitive("android"))
            put("screen_or_route", JsonPrimitive(event.screenOrRoute))
            put("release_channel", JsonPrimitive("production"))
            put(
                "properties",
                buildJsonObject { extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
            )
        }
    }

    private companion object {
        const val MAX_PER_FLUSH = 200
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
