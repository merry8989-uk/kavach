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
)
