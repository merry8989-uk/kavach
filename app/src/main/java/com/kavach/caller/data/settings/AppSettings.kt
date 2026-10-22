package com.kavach.caller.data.settings

/**
 * Persisted user settings. Backed by DataStore (see [SettingsRepository]).
 * Every field has a safe default so the app works before onboarding is done.
 */
data class AppSettings(
    val themePack: String = "AURORA",
    val announcerMode: String = "KNOWN_ONLY",
    val announceSpamVerdict: Boolean = true,
    val cloudAiEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Opt-in local diagnostics log. Nothing is ever sent without the user sharing it. */
    val diagnosticsEnabled: Boolean = true,

    /**
     * Kavach server for online lookup and reporting (F9.3+). Empty means "no
     * server", and every backend-backed feature reports itself unavailable
     * rather than failing quietly. Kavach ships no hosted backend.
     */
    val backendBaseUrl: String = "",
    /** Bearer token for that server. Stored in DataStore; see SECURITY.md. */
    val backendToken: String = "",

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

    /**
     * Write diagnostics events straight into Supabase. No server to run: the
     * app appends to a Postgres table over PostgREST.
     */

    /**
     * Send diagnostics events to the Kavach server instead of (or as well as)
     * the Supabase path.
     */

)
