package com.kavach.caller.ai

import android.telecom.Call
import com.kavach.caller.data.local.entity.TranscriptEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.service.Announcer
import com.kavach.caller.util.PhoneUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the AI call assistant (F10). Ties screening -> answering ->
 * transcription -> dialogue -> auto-SMS -> post-call brief together.
 *
 * States: IDLE -> RINGING -> ANSWERED -> LISTENING -> DIALOGUE -> SUMMARISING -> DONE
 *
 * AI enablement and cloud consent now come from persisted settings.
 */
@Singleton
class AssistantOrchestrator @Inject constructor(
    private val screener: OnDeviceScreener,
    private val cloud: CloudAiClient,
    private val spamScorer: SpamScorer,
    private val contacts: ContactRepository,
    private val callLog: CallLogRepository,
    private val announcer: Announcer,
    private val settings: SettingsRepository,
    private val features: FeatureRegistry,
) {
    enum class State { IDLE, RINGING, ANSWERED, LISTENING, DIALOGUE, SUMMARISING, DONE }

    @Volatile var state: State = State.IDLE
        private set

    private val pending = ArrayDeque<Pair<String, Call.Details>>()

    /** Called by the screening service when a call should go to the assistant. */
    fun queueForAssistant(number: String, details: Call.Details) {
        pending.addLast(number to details)
        state = State.RINGING
    }

    /** Drain the next call waiting for the assistant, if any. */
    fun nextPending(): Pair<String, Call.Details>? {
        val next = pending.removeFirstOrNull()
        if (next != null) state = State.ANSWERED
        return next
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /**
     * Build the greeting the assistant will speak when it answers.
     * Personalised by whether we know the caller.
     */
    suspend fun buildDecision(number: String): AssistantDecision {
        val name = contacts.nameFor(number)
        val isContact = name != null
        val score = spamScorer.score(number, isContact = isContact)
        val answer = screener.shouldAnswer(
            isContact = isContact,
            spamScore = score,
            aiEnabled = features.isEnabled(FeatureId.AI_ASSISTANT),
        )
        val greeting = if (isContact) {
            "Hi, this is $name's Kavach assistant. They can't take the call right now. How can I help?"
        } else {
            "Hello, you've reached a Kavach-protected number. The owner is unavailable. May I ask who's calling and what this is regarding?"
        }
        return AssistantDecision(answer = answer, greeting = greeting)
    }

    /**
     * Called as audio arrives. On-device we classify intent in real time;
     * with cloud enabled we stream to the ASR endpoint for a full transcript.
     */
    suspend fun onUtterance(number: String, utterance: String): CallIntent {
        state = State.LISTENING
        val local = screener.classify(utterance)
        if (!settings.snapshot.cloudAiEnabled) return local
        // Integration point: CloudAiClient.transcribe(...) for the full
        // transcript, then CloudAiClient.nextLine(...) for the reply.
        return local
    }

    /** Finish the call: produce the brief and persist it. */
    suspend fun finish(
        number: String,
        transcript: String,
        intent: CallIntent,
        recordingPath: String?,
    ): CallBrief {
        state = State.SUMMARISING
        val brief = if (settings.snapshot.cloudAiEnabled) {
            cloud.summarise(transcript, number)
        } else null
        val result = brief ?: CallBrief(
            number = number,
            transcript = transcript,
            summary = summariseLocally(transcript, intent),
            intent = intent,
            risk = spamScorer.score(number),
            recordingPath = recordingPath,
        )
        callLog.saveTranscript(
            TranscriptEntity(
                number = number,
                timestamp = System.currentTimeMillis(),
                transcript = transcript,
                summary = result.summary,
                intent = result.intent.name,
                sentiment = result.sentiment.name,
                risk = result.risk,
            )
        )
        announcer.speak("Call handled. Summary ready.")
        state = State.DONE
        return result
    }

    private fun summariseLocally(transcript: String, intent: CallIntent): String {
        val who = "Caller"
        return when (intent) {
            CallIntent.DELIVERY -> "$who had a delivery for you. Details in transcript."
            CallIntent.APPOINTMENT -> "$who wanted to schedule an appointment."
            CallIntent.EMERGENCY -> "$who indicated this was urgent."
            CallIntent.SALES -> "$who was a sales/marketing call."
            CallIntent.SPAM -> "Likely spam call, handled automatically."
            CallIntent.PERSONAL -> "$who called for a personal matter."
            CallIntent.UNKNOWN -> "Caller did not state a clear purpose."
        }
    }

    fun prettyNumber(number: String): String = PhoneUtils.pretty(number)
}
