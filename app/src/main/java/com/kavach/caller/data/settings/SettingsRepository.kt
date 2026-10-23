package com.kavach.caller.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import androidx.datastore.preferences.core.intPreferencesKey

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kavach_settings")

/**
 * Single source of truth for user settings.
 *
 * Settings are a Flow for the UI, but background components (the screening
 * service, the announcer) need them synchronously — so we also keep a
 * [snapshot] updated by an internal collector. This replaces the old
 * `remember { }`-only settings that reset on every launch.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        /**
         * Kept here rather than imported from ui/theme, because the data layer
         * must not depend on the UI. The two lists are checked against each other
         * by a test so they cannot drift.
         */
        val MOTION_MODES = setOf("system", "full", "reduced")

        /** Mirrors BlockAction's names; checked against it by a test. */
        val AUTO_BLOCK_ACTIONS = setOf("silence", "block", "warn")
    }

    private object Keys {
        val themePack = stringPreferencesKey("theme_pack")
        val motionMode = stringPreferencesKey("motion_mode")
        val autoBlockScore = intPreferencesKey("auto_block_score")
        val autoBlockAction = stringPreferencesKey("auto_block_action")
        val announcerMode = stringPreferencesKey("announcer_mode")
        val announceSpamVerdict = booleanPreferencesKey("announce_spam_verdict")
        val cloudAiEnabled = booleanPreferencesKey("cloud_ai_enabled")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val diagnosticsEnabled = booleanPreferencesKey("diagnostics_enabled")
        val backendBaseUrl = stringPreferencesKey("backend_base_url")
        val accountToken = stringPreferencesKey("account_token")
        val accountUserId = stringPreferencesKey("account_user_id")
        val accountDisplayName = stringPreferencesKey("account_display_name")
        val accountPublishName = booleanPreferencesKey("account_publish_name")
        val offlineOnly = booleanPreferencesKey("offline_only")
        val dataSaver = booleanPreferencesKey("data_saver")
        val suggestedReplies = stringPreferencesKey("suggested_replies")
        val autoReplyEnabled = booleanPreferencesKey("auto_reply_enabled")
        val autoReplyText = stringPreferencesKey("auto_reply_text")
        val smsWarnOnLinks = booleanPreferencesKey("sms_warn_on_links")
        val smsWarnOnSpam = booleanPreferencesKey("sms_warn_on_spam")
        val smsShowOtpBanner = booleanPreferencesKey("sms_show_otp_banner")
        val recordingConsentGiven = booleanPreferencesKey("recording_consent_given")
        val recordingConsentAt = longPreferencesKey("recording_consent_at")
        val hiddenSmsIds = stringSetPreferencesKey("hidden_sms_ids")
        val featureOverrides = stringSetPreferencesKey("feature_overrides")
    }

    /**
     * Per-feature on/off overrides (F16). Stored as "FEATURE_NAME=true" entries so
     * the registry can keep them in one key and stay forward-compatible when new
     * features are added.
     */
    val featureOverrides: Flow<Map<String, Boolean>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.featureOverrides] ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split('=')
            if (parts.size != 2) return@mapNotNull null
            parts[0] to (parts[1].toBooleanStrictOrNull() ?: true)
        }.toMap()
    }

    suspend fun setFeatureOverride(feature: String, enabled: Boolean) =
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.featureOverrides] ?: emptySet()).toMutableSet()
            current.removeAll { it.startsWith("$feature=") }
            current.add("$feature=$enabled")
            prefs[Keys.featureOverrides] = current
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themePack = p[Keys.themePack] ?: "AURORA",
            motionMode = p[Keys.motionMode] ?: "system",
            autoBlockScore = (p[Keys.autoBlockScore] ?: 0).coerceIn(0, 100),
            autoBlockAction = (p[Keys.autoBlockAction] ?: "silence").takeIf { it in AUTO_BLOCK_ACTIONS }
                ?: "silence",
            announcerMode = p[Keys.announcerMode] ?: "KNOWN_ONLY",
            announceSpamVerdict = p[Keys.announceSpamVerdict] ?: true,
            cloudAiEnabled = p[Keys.cloudAiEnabled] ?: false,
            onboardingDone = p[Keys.onboardingDone] ?: false,
            diagnosticsEnabled = p[Keys.diagnosticsEnabled] ?: true,
            backendBaseUrl = p[Keys.backendBaseUrl] ?: "",
            accountToken = p[Keys.accountToken] ?: "",
            accountUserId = p[Keys.accountUserId] ?: "",
            accountDisplayName = p[Keys.accountDisplayName] ?: "",
            accountPublishName = p[Keys.accountPublishName] ?: false,
            offlineOnly = p[Keys.offlineOnly] ?: false,
            dataSaver = p[Keys.dataSaver] ?: false,
            suggestedReplies = p[Keys.suggestedReplies] ?: "",
            autoReplyEnabled = p[Keys.autoReplyEnabled] ?: false,
            autoReplyText = p[Keys.autoReplyText] ?: "I'll get back to you soon.",
            smsWarnOnLinks = p[Keys.smsWarnOnLinks] ?: true,
            smsWarnOnSpam = p[Keys.smsWarnOnSpam] ?: true,
            smsShowOtpBanner = p[Keys.smsShowOtpBanner] ?: true,
            recordingConsentGiven = p[Keys.recordingConsentGiven] ?: false,
            recordingConsentAt = p[Keys.recordingConsentAt] ?: 0L,
            hiddenSmsIds = p[Keys.hiddenSmsIds] ?: emptySet(),
        )
    }

    /** Latest values, readable without suspending. */
    @Volatile
    var snapshot: AppSettings = AppSettings()
        private set

    init {
        scope.launch { settings.collect { snapshot = it } }
    }

    suspend fun setThemePack(value: String) = context.dataStore.edit { it[Keys.themePack] = value }

    /** "system" | "full" | "reduced". Unknown values fall back to "system". */
    /** 0 turns score-based action off entirely. */
    suspend fun setAutoBlockScore(value: Int) = context.dataStore.edit {
        it[Keys.autoBlockScore] = value.coerceIn(0, 100)
    }

    suspend fun setAutoBlockAction(value: String) = context.dataStore.edit {
        it[Keys.autoBlockAction] = value.takeIf { v -> v in AUTO_BLOCK_ACTIONS } ?: "silence"
    }

    suspend fun setMotionMode(value: String) = context.dataStore.edit {
        it[Keys.motionMode] = value.takeIf { v -> v in MOTION_MODES } ?: "system"
    }
    suspend fun setAnnouncerMode(value: String) = context.dataStore.edit { it[Keys.announcerMode] = value }
    suspend fun setAnnounceSpamVerdict(value: Boolean) = context.dataStore.edit { it[Keys.announceSpamVerdict] = value }
    suspend fun setCloudAiEnabled(value: Boolean) = context.dataStore.edit { it[Keys.cloudAiEnabled] = value }
    suspend fun setOnboardingDone(value: Boolean) = context.dataStore.edit { it[Keys.onboardingDone] = value }
    suspend fun setDiagnosticsEnabled(value: Boolean) = context.dataStore.edit { it[Keys.diagnosticsEnabled] = value }
    suspend fun setBackendBaseUrl(value: String) = context.dataStore.edit { it[Keys.backendBaseUrl] = value.trim() }

    suspend fun setOfflineOnly(value: Boolean) = context.dataStore.edit { it[Keys.offlineOnly] = value }
    suspend fun setSuggestedReplies(json: String) = context.dataStore.edit { it[Keys.suggestedReplies] = json }
    suspend fun setAutoReplyEnabled(value: Boolean) = context.dataStore.edit { it[Keys.autoReplyEnabled] = value }
    suspend fun setAutoReplyText(value: String) = context.dataStore.edit { it[Keys.autoReplyText] = value }
    suspend fun hideSmsIds(ids: Set<String>) = context.dataStore.edit { prefs ->
        prefs[Keys.hiddenSmsIds] = (prefs[Keys.hiddenSmsIds] ?: emptySet()) + ids
    }
    suspend fun clearHiddenSms() = context.dataStore.edit { it.remove(Keys.hiddenSmsIds) }
    suspend fun setHiddenSmsIds(ids: Set<String>) = context.dataStore.edit { it[Keys.hiddenSmsIds] = ids }
    suspend fun setSmsWarnOnLinks(value: Boolean) = context.dataStore.edit { it[Keys.smsWarnOnLinks] = value }
    suspend fun setSmsWarnOnSpam(value: Boolean) = context.dataStore.edit { it[Keys.smsWarnOnSpam] = value }
    suspend fun setSmsShowOtpBanner(value: Boolean) = context.dataStore.edit { it[Keys.smsShowOtpBanner] = value }
    suspend fun setRecordingConsent(given: Boolean) = context.dataStore.edit {
        it[Keys.recordingConsentGiven] = given
        it[Keys.recordingConsentAt] = if (given) System.currentTimeMillis() else 0L
    }

    suspend fun setDataSaver(value: Boolean) = context.dataStore.edit { it[Keys.dataSaver] = value }

    /** Persist the signed-in account. Passing blanks signs the user out. */
    suspend fun setAccount(
        token: String,
        userId: String,
        displayName: String,
        publishName: Boolean,
    ) = context.dataStore.edit {
        it[Keys.accountToken] = token
        it[Keys.accountUserId] = userId
        it[Keys.accountDisplayName] = displayName
        it[Keys.accountPublishName] = publishName
    }
}
