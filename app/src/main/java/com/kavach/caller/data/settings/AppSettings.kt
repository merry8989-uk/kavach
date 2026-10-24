package com.kavach.caller.data.settings

/**
 * Persisted user settings. Backed by DataStore (see [SettingsRepository]).
 * Every field has a safe default so the app works before onboarding is done.
 */
data class AppSettings(
    val themePack: String = "AURORA",
    /**
     * How much motion to use: "system", "full" or "reduced".
     *
     * "system" is the default and it means *follow Android's own setting*. If the
     * user has turned animations off in Accessibility — which is what people with
     * vestibular disorders do — Kavach does not animate, without being told twice.
     */
    val motionMode: String = "system",

    /**
     * Act on the reputation score alone: 0 means never.
     *
     * **Zero is the default, and that is the point.** The review was explicit —
     * *"sirf reputation score ke basis par genuine calls automatically block na
     * hon; threshold aur action user control kare."* A score is other people's
     * opinion plus pattern rules; it is not evidence, and a genuine call that a
     * few people wrongly reported should not be silently killed.
     *
     * So rules block by default, and the score only acts if the user asks it to,
     * at a threshold they choose.
     */
    /**
     * Sample high-volume events: **off by default**.
     *
     * The specification asks for "100% of eligible events", and off is what that
     * means. Sampling was on before, with a volume-aware rule that kept
     * everything below 120 events a minute — sensible, and still loss: a busy
     * minute discarded events the user never saw go.
     *
     * Turning this on is the user's call, and it is the only thing that ever
     * discards a non-critical event.
     */
    val diagnosticsSamplingEnabled: Boolean = false,

    /**
     * Delete events older than this many days. **0 means never.**
     *
     * Zero is the shipped default: nothing is deleted. A log that quietly forgets
     * the day you needed it is a sample, not a log. The cost is storage, and the
     * user is the one who should decide to pay it.
     */
    val diagnosticsRetentionDays: Int = 0,

    val autoBlockScore: Int = 0,

    /**
     * What to do when the score crosses [autoBlockScore]:
     * "silence" | "block" | "warn".
     *
     * Silence is the default because it is the gentlest of the three: the call
     * still arrives and is still logged, it just does not ring.
     */
    val autoBlockAction: String = "silence",
    val announcerMode: String = "KNOWN_ONLY",
    val announceSpamVerdict: Boolean = true,
    val cloudAiEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    /**
     * The local diagnostics log. **On by default.**
     *
     * The comment here used to say "opt-in", which contradicted the value and had
     * done for several releases. Corrected to say what is true.
     *
     * On is the right default: the log never leaves the device, so collecting it
     * costs the user nothing in privacy, and a log that is off until someone
     * finds the setting is a log that is missing on the day it was needed.
     * Turning it off deletes nothing — it stops new events being written.
     */
    val diagnosticsEnabled: Boolean = true,

    /**
     * Kavach server for online lookup and reporting (F9.3+). Empty means "no
     * server", and every backend-backed feature reports itself unavailable
     * rather than failing quietly. Kavach ships no hosted backend.
     */
    val backendBaseUrl: String = "",
    /** Bearer token for that server. Stored in DataStore; see SECURITY.md. */

    /**
     * Consent-based account (docs/CALLER-ID-PLAN.md).
     * The token is the per-user JWT from the OTP flow; the rest mirrors what the
     * user last told the server, so the UI can render before a network round trip.
     */
    val accountToken: String = "",
    val accountUserId: String = "",
    val accountDisplayName: String = "",
    val accountPublishName: Boolean = false,

    /**
     * F16.4 power-saving modes. Both default off, and both change real
     * behaviour rather than only the UI:
     *  - offline-only stops every network call the app would otherwise make;
     *  - data saver stops the background sync and the launch-time update check.
     */
    val offlineOnly: Boolean = false,
    val dataSaver: Boolean = false,

    /** Suggested replies, stored as a JSON array so order is preserved. */
    val suggestedReplies: String = "",
    /** Auto-reply to incoming SMS. Off by default — it sends without asking. */
    val autoReplyEnabled: Boolean = false,
    val autoReplyText: String = "I'll get back to you soon.",
    /**
     * SMS warning controls (spec Phase 8: "SMS notification controls").
     *
     * These gate the in-app warnings, which Kavach fully controls. Changing the
     * *system* notification for a message requires being the default SMS app,
     * which Kavach does not claim to be.
     */
    val smsWarnOnLinks: Boolean = true,
    val smsWarnOnSpam: Boolean = true,
    val smsShowOtpBanner: Boolean = true,

    /**
     * Explicit consent before the first recording (spec Phase 9: never record
     * silently). Until this is true, Kavach refuses to start a recording and
     * says why instead.
     */
    val recordingConsentGiven: Boolean = false,
    val recordingConsentAt: Long = 0L,

    /** Message ids the user removed from Kavach's view. See MessagesScreen. */
    val hiddenSmsIds: Set<String> = emptySet(),

    // ---- messaging, in the shape the stock app uses ----
    //
    // Every one of these changes something real. Where a setting cannot work
    // here, it is not offered at all — see MessagesSettingsScreen for the RCS
    // case, which is the one that matters.

    /** system | light | dark. Applies to the conversation list and threads. */
    val smsTheme: String = "system",

    /** Play a sound for sent and received messages. */
    val smsSoundsEnabled: Boolean = true,

    /**
     * Ask the carrier for a delivery report on each sent message.
     *
     * The SMS equivalent of a read receipt, and the only one that exists over
     * SMS: a delivery report says the handset received it, never that a person
     * read it. The screen says so rather than implying more.
     */
    val smsDeliveryReports: Boolean = false,

    /** Retry a failed send automatically. */
    val smsAutoRetry: Boolean = true,

    /** all | selected | none. Notification bubbles, which Android supports. */
    val smsBubbles: String = "selected",

    /** archive | delete | none — what a swipe on a conversation does. */
    val smsSwipeAction: String = "archive",

    /** Show a preview card for links in a conversation. */
    val smsLinkPreviews: Boolean = true,

    /** Group the conversation list into Today / Yesterday / Older. */
    val smsGroupByTime: Boolean = true,

    /**
     * Conversations the user allowed to bubble.
     *
     * Only consulted when the bubble setting is "selected". Without it that
     * option had nothing to select, so it behaved like "none" while claiming to
     * be a middle ground.
     */
    val bubbledSmsThreads: Set<String> = emptySet(),

    /** Conversations the user archived. */
    val archivedSmsThreads: Set<String> = emptySet(),

    /** Conversations the user put in the bin. */
    val binnedSmsThreads: Set<String> = emptySet(),

    /** When the user last marked everything read, so the badge can honour it. */
    val smsMarkedAllReadAt: Long = 0,

    /**
     * Write diagnostics events straight into Supabase. No server to run: the
     * app appends to a Postgres table over PostgREST.
     */

    /**
     * Send diagnostics events to the Kavach server instead of (or as well as)
     * the Supabase path.
     */

)
