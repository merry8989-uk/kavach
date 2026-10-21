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
)
