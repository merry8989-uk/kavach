package com.kavach.caller.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    fun setThemePack(value: String) = viewModelScope.launch { repo.setThemePack(value) }
    fun setAnnouncerMode(value: String) = viewModelScope.launch { repo.setAnnouncerMode(value) }
    fun setAnnounceSpamVerdict(value: Boolean) = viewModelScope.launch { repo.setAnnounceSpamVerdict(value) }
    fun setAiEnabled(value: Boolean) = viewModelScope.launch { repo.setAiEnabled(value) }
    fun setCloudAiEnabled(value: Boolean) = viewModelScope.launch { repo.setCloudAiEnabled(value) }
    fun setRecordAll(value: Boolean) = viewModelScope.launch { repo.setRecordAll(value) }
}
