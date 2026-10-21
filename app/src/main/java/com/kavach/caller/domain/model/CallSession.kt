package com.kavach.caller.domain.model

/** Which way the call is going (F4 caller UI). */
enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

/** Where the call is in its lifecycle. */
enum class CallPhase { RINGING, DIALING, ACTIVE, HOLDING, ENDED, UNKNOWN }

/**
 * The live state of the current call.
 *
 * This is what the in-call screen and the call notification render, so it
 * carries everything the user needs to answer "who is this, on which SIM, which
 * way round, and for how long".
 */
data class CallSession(
    val number: String,
    /** Contact name if we know one; null means genuinely unknown. */
    val displayName: String? = null,
    val isKnownContact: Boolean = false,
    val direction: CallDirection = CallDirection.UNKNOWN,
    val phase: CallPhase = CallPhase.UNKNOWN,
    /** Human-readable SIM label, e.g. "Jio 4G" or "SIM 2". Null when unknown. */
    val simLabel: String? = null,
    /** When the call connected — drives the live duration timer. */
    val connectedAtMillis: Long? = null,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
) {
    /** What to show as the headline: the name, else the number, else a placeholder. */
    val title: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: number.takeIf { it.isNotBlank() }
            ?: "Unknown caller"

    val directionLabel: String
        get() = when (direction) {
            CallDirection.INCOMING -> "Incoming"
            CallDirection.OUTGOING -> "Outgoing"
            CallDirection.UNKNOWN -> "Call"
        }

    /** "Ringing…", "Dialling…", "In call", "On hold". */
    val phaseLabel: String
        get() = when (phase) {
            CallPhase.RINGING -> "Ringing"
            CallPhase.DIALING -> "Dialling"
            CallPhase.ACTIVE -> "In call"
            CallPhase.HOLDING -> "On hold"
            CallPhase.ENDED -> "Call ended"
            CallPhase.UNKNOWN -> "Call"
        }

    val isConnected: Boolean
        get() = phase == CallPhase.ACTIVE || phase == CallPhase.HOLDING
}
