package com.kavach.caller.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud half of the hybrid AI (F10.3/F10.6/F10.7). Talks to Kavach's own
 * backend, which proxies an ASR provider (Whisper-class) and an LLM for
 * summaries + dialogue. Nothing is sent unless the user enabled cloud AI.
 *
 * The backend base URL is injected at build time via BuildConfig so no
 * secrets live in the repo.
 */
interface KavachAiApi {
    @POST("v1/transcribe")
    suspend fun transcribe(@Body req: TranscribeRequest): TranscribeResponse

    @POST("v1/summarise")
    suspend fun summarise(@Body req: SummariseRequest): CallBrief

    @POST("v1/dialogue")
    suspend fun dialogue(@Body req: DialogueRequest): DialogueResponse
}

@Serializable data class TranscribeRequest(val audioBase64: String, val language: String = "en-IN")
@Serializable data class TranscribeResponse(val transcript: String, val confidence: Float = 0f)
@Serializable data class SummariseRequest(val transcript: String, val number: String)
@Serializable data class DialogueRequest(val transcriptSoFar: String, val callerName: String? = null)
@Serializable data class DialogueResponse(val say: String, val shouldEscalate: Boolean = false)

@Singleton
class CloudAiClient @Inject constructor(
    okHttp: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val api: KavachAiApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(KavachAiApi::class.java)

    /** Returns null on any failure — callers fall back to the on-device path. */
    suspend fun transcribe(audioBase64: String): String? =
        runCatching { api.transcribe(TranscribeRequest(audioBase64)).transcript }.getOrNull()

    suspend fun summarise(transcript: String, number: String): CallBrief? =
        runCatching { api.summarise(SummariseRequest(transcript, number)) }.getOrNull()

    suspend fun nextLine(transcript: String, callerName: String?): DialogueResponse? =
        runCatching { api.dialogue(DialogueRequest(transcript, callerName)) }.getOrNull()

    companion object {
        // Point this at your deployed Kavach backend.
        const val BASE_URL = "https://api.kavach.app/"
    }
}
