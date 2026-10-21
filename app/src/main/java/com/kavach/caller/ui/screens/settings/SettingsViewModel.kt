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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val callStateMonitor: CallStateMonitor,
    /** Exposed so the nav graph can gate work on the feature registry (F16). */
    val features: FeatureRegistry,
) : ViewModel() {

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
    fun setAnnouncerMode(value: String) = viewModelScope.launch { repo.setAnnouncerMode(value) }
    fun setAnnounceSpamVerdict(value: Boolean) = viewModelScope.launch { repo.setAnnounceSpamVerdict(value) }
    fun setCloudAiEnabled(value: Boolean) = viewModelScope.launch { repo.setCloudAiEnabled(value) }
    fun setOnboardingDone(value: Boolean) = viewModelScope.launch { repo.setOnboardingDone(value) }
    fun setDiagnosticsEnabled(value: Boolean) = viewModelScope.launch { repo.setDiagnosticsEnabled(value) }
    fun setBackendBaseUrl(value: String) = viewModelScope.launch { repo.setBackendBaseUrl(value) }
    fun setBackendToken(value: String) = viewModelScope.launch { repo.setBackendToken(value) }
}
