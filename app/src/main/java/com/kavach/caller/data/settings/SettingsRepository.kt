package com.kavach.caller.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    private object Keys {
        val themePack = stringPreferencesKey("theme_pack")
        val announcerMode = stringPreferencesKey("announcer_mode")
        val announceSpamVerdict = booleanPreferencesKey("announce_spam_verdict")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val cloudAiEnabled = booleanPreferencesKey("cloud_ai_enabled")
        val recordAll = booleanPreferencesKey("record_all")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themePack = p[Keys.themePack] ?: "AURORA",
            announcerMode = p[Keys.announcerMode] ?: "KNOWN_ONLY",
            announceSpamVerdict = p[Keys.announceSpamVerdict] ?: true,
            aiEnabled = p[Keys.aiEnabled] ?: true,
            cloudAiEnabled = p[Keys.cloudAiEnabled] ?: false,
            recordAll = p[Keys.recordAll] ?: false,
            onboardingDone = p[Keys.onboardingDone] ?: false,
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
    suspend fun setAnnouncerMode(value: String) = context.dataStore.edit { it[Keys.announcerMode] = value }
    suspend fun setAnnounceSpamVerdict(value: Boolean) = context.dataStore.edit { it[Keys.announceSpamVerdict] = value }
    suspend fun setAiEnabled(value: Boolean) = context.dataStore.edit { it[Keys.aiEnabled] = value }
    suspend fun setCloudAiEnabled(value: Boolean) = context.dataStore.edit { it[Keys.cloudAiEnabled] = value }
    suspend fun setRecordAll(value: Boolean) = context.dataStore.edit { it[Keys.recordAll] = value }
    suspend fun setOnboardingDone(value: Boolean) = context.dataStore.edit { it[Keys.onboardingDone] = value }
}
