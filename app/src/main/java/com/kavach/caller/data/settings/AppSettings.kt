package com.kavach.caller.data.settings

/**
 * Persisted user settings. Backed by DataStore (see [SettingsRepository]).
 * Every field has a safe default so the app works before onboarding is done.
 */
data class AppSettings(
    val themePack: String = "AURORA",
    val announcerMode: String = "KNOWN_ONLY",
    val announceSpamVerdict: Boolean = true,
    val aiEnabled: Boolean = true,
    val cloudAiEnabled: Boolean = false,
    val recordAll: Boolean = false,
    val onboardingDone: Boolean = false,
    val autoCheckUpdates: Boolean = true,
)
