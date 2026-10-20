package com.kavach.caller.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class TranscribeRequest(val audioBase64: String, val language: String = "en-IN")
@Serializable data class TranscribeResponse(val transcript: String, val confidence: Float = 0f)
@Serializable data class SummariseRequest(val transcript: String, val number: String)
@Serializable data class DialogueRequest(val transcriptSoFar: String, val callerName: String? = null)
@Serializable data class DialogueResponse(val say: String, val shouldEscalate: Boolean = false)

/**
 * Cloud half of the hybrid AI (F10.3/F10.6/F10.7). Talks to Kavach's own
 * backend over plain HTTPS with OkHttp + kotlinx.serialization — no Retrofit
 * converter to keep the dependency surface small.
 *
 * Nothing is sent unless the user enabled cloud AI. Every call fails soft
 * (returns null) so the on-device path always works offline.
 */
@Singleton
class CloudAiClient @Inject constructor(
    private val okHttp: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    private suspend fun postRaw(path: String, payload: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(BASE_URL + path)
                    .post(payload.toRequestBody(mediaType))
                    .build()
                okHttp.newCall(request).execute().use { response ->
                    response.body?.string()
                }
            }.getOrNull()
        }

    /** Full transcript of a recorded/screened call. */
    suspend fun transcribe(audioBase64: String): String? {
        val raw = postRaw("v1/transcribe", json.encodeToString(TranscribeRequest(audioBase64)))
            ?: return null
        return runCatching { json.decodeFromString<TranscribeResponse>(raw).transcript }.getOrNull()
    }

    /** Structured brief: summary + intent + sentiment + risk. */
    suspend fun summarise(transcript: String, number: String): CallBrief? {
        val raw = postRaw("v1/summarise", json.encodeToString(SummariseRequest(transcript, number)))
            ?: return null
        return runCatching { json.decodeFromString<CallBrief>(raw) }.getOrNull()
    }

    /** The assistant's next spoken line, and whether to escalate to the owner. */
    suspend fun nextLine(transcript: String, callerName: String?): DialogueResponse? {
        val raw = postRaw("v1/dialogue", json.encodeToString(DialogueRequest(transcript, callerName)))
            ?: return null
        return runCatching { json.decodeFromString<DialogueResponse>(raw) }.getOrNull()
    }

    companion object {
        // Point this at your deployed Kavach backend.
        const val BASE_URL = "https://api.kavach.app/"
    }
}
