package com.kavach.caller.ai

import kotlinx.serialization.Serializable

/** Intent the assistant infers from a screened call (F10.4). */
@Serializable
enum class CallIntent { DELIVERY, APPOINTMENT, EMERGENCY, SALES, SPAM, PERSONAL, UNKNOWN }

@Serializable
enum class Sentiment { POSITIVE, NEUTRAL, NEGATIVE }

/** The structured brief Kavach delivers after an AI-answered call (F10.7). */
@Serializable
data class CallBrief(
    val number: String,
    val transcript: String,
    val summary: String,
    val intent: CallIntent = CallIntent.UNKNOWN,
    val sentiment: Sentiment = Sentiment.NEUTRAL,
    val risk: Int = 0,
    val recordingPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/** A caller identity result, from local DB or cloud (F9). */
data class CallerIdentity(
    val number: String,
    val name: String? = null,
    val company: String? = null,
    val tags: List<String> = emptyList(),
    val spamScore: Int = 0,
    val reports: Int = 0,
    val source: Source = Source.LOCAL,
) {
    enum class Source { LOCAL, CLOUD, CONTACT, NONE }
}

/** What the assistant should do with an incoming call. */
data class AssistantDecision(
    val answer: Boolean,
    val greeting: String,
    val autoSms: String? = null,
)
