package com.kavach.caller.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device screening (F10.1/F10.3 local half). Uses a lightweight keyword
 * model so screening works with zero network and zero cost.
 *
 * In production this is backed by a TFLite text-classification model
 * (assets/intent_model.tflite). The keyword fallback below keeps the app
 * functional before the model ships, and is what the scaffold runs.
 */
@Singleton
class OnDeviceScreener @Inject constructor() {

    private val deliveryWords = listOf("delivery", "parcel", "courier", "package", "amazon", "flipkart", "swiggy", "zomato")
    private val emergencyWords = listOf("emergency", "hospital", "accident", "urgent", "help")
    private val salesWords = listOf("loan", "credit card", "insurance", "offer", "policy", "investment", "kyc")
    private val appointmentWords = listOf("appointment", "booking", "schedule", "meeting")

    /** Cheap intent guess from a short utterance. */
    fun classify(utterance: String): CallIntent {
        val t = utterance.lowercase()
        return when {
            emergencyWords.any { t.contains(it) } -> CallIntent.EMERGENCY
            deliveryWords.any { t.contains(it) } -> CallIntent.DELIVERY
            salesWords.any { t.contains(it) } -> CallIntent.SPAM
            appointmentWords.any { t.contains(it) } -> CallIntent.APPOINTMENT
            t.isBlank() -> CallIntent.UNKNOWN
            else -> CallIntent.PERSONAL
        }
    }

    /** Should the assistant answer this call? */
    fun shouldAnswer(isContact: Boolean, spamScore: Int, aiEnabled: Boolean): Boolean =
        aiEnabled && (!isContact || spamScore > 40)
}
