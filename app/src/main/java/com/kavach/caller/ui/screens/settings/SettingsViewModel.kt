package com.kavach.caller.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.service.CallStateMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.kavach.caller.domain.DefaultRoleNudge

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val callStateMonitor: CallStateMonitor,
    /** Exposed so the nav graph can gate work on the feature registry (F16). */
    val features: FeatureRegistry,
    /** Exposed so the nav graph can log screen views. */
    val diagnostics: com.kavach.caller.data.diagnostics.DiagnosticsRepository,
    val session: com.kavach.caller.data.diagnostics.SessionContext,
    private val secrets: com.kavach.caller.data.secure.SecretStore,
    private val api: com.kavach.caller.data.remote.KavachApiClient,
) : ViewModel() {

    // ---------------------------------------------------- self-hosted lookup --

    /**
     * The result of the last connection test, in the server's own terms.
     *
     * Null means "not tested since the last edit" — not "failed". Conflating the
     * two is how a settings screen ends up showing a red error for a field the
     * user has not filled in yet.
     */
    var serverTestResult by mutableStateOf<String?>(null)
        private set

    var serverTesting by mutableStateOf(false)
        private set

    /**
     * The user chose "later".
     *
     * Schedules the next ask two days out and **does nothing else** — no nag on
     * the way out of the screen, because they just answered.
     */
    fun setDefaultLater() = viewModelScope.launch {
        repo.setDefaultNudgeAt(DefaultRoleNudge.nextAskAt(System.currentTimeMillis()))
    }

    /** They granted it, so there is nothing left to remind them about. */
    fun clearDefaultNudge() = viewModelScope.launch {
        repo.setDefaultNudgeAt(0L)
    }

    fun setServerUrl(value: String) = viewModelScope.launch { repo.setBackendBaseUrl(value) }

    fun setServerToken(value: String) {
        // Straight to the Keystore, never to DataStore.
        secrets.put(com.kavach.caller.data.secure.SecretStore.SERVER_TOKEN, value)
        serverTestResult = null
    }

    /** Does a token exist? Used to show "set" without ever reading the value back. */
    fun hasServerToken(): Boolean =
        secrets.has(com.kavach.caller.data.secure.SecretStore.SERVER_TOKEN)

    fun clearServerToken() {
        secrets.put(com.kavach.caller.data.secure.SecretStore.SERVER_TOKEN, null)
        serverTestResult = null
    }

    /**
     * Ask the server whether it is actually there.
     *
     * Deliberately a real request to `/health/ready` rather than a URL syntax
     * check: a well-formed address to a server that is down is the failure this
     * button exists to catch.
     */
    fun testServerConnection(url: String, token: String) = viewModelScope.launch {
        serverTesting = true
        serverTestResult = api.testConnection(url, token)
        serverTesting = false
    }

    /**
     * Start the fallback call monitor. Called once the app is past onboarding,
     * so READ_PHONE_STATE has actually been granted by then.
     */
    fun startCallMonitoring() = callStateMonitor.start()

    val settings = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    /**
     * True once DataStore has produced its first real value. The nav graph waits
     * on this so the onboarding screen never flashes on a launch where setup is
     * already complete.
     */
    val loaded = repo.settings
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setThemePack(value: String) = viewModelScope.launch { repo.setThemePack(value) }

    fun setMotionMode(value: String) = viewModelScope.launch { repo.setMotionMode(value) }

    fun setAutoBlockScore(value: Int) = viewModelScope.launch { repo.setAutoBlockScore(value) }

    fun setAutoBlockAction(value: String) = viewModelScope.launch { repo.setAutoBlockAction(value) }
    fun setAnnouncerMode(value: String) = viewModelScope.launch { repo.setAnnouncerMode(value) }
    fun setAnnounceSpamVerdict(value: Boolean) = viewModelScope.launch { repo.setAnnounceSpamVerdict(value) }
    fun setCloudAiEnabled(value: Boolean) = viewModelScope.launch { repo.setCloudAiEnabled(value) }
    fun setOnboardingDone(value: Boolean) = viewModelScope.launch { repo.setOnboardingDone(value) }
    fun setDiagnosticsEnabled(value: Boolean) = viewModelScope.launch { repo.setDiagnosticsEnabled(value) }
    fun setOfflineOnly(value: Boolean) = viewModelScope.launch { repo.setOfflineOnly(value) }
    fun setDataSaver(value: Boolean) = viewModelScope.launch { repo.setDataSaver(value) }
    fun setSmsWarnOnLinks(value: Boolean) = viewModelScope.launch { repo.setSmsWarnOnLinks(value) }
    fun setSmsWarnOnSpam(value: Boolean) = viewModelScope.launch { repo.setSmsWarnOnSpam(value) }
    fun setSmsShowOtpBanner(value: Boolean) = viewModelScope.launch { repo.setSmsShowOtpBanner(value) }
    fun setBackendBaseUrl(value: String) = viewModelScope.launch { repo.setBackendBaseUrl(value) }
}
