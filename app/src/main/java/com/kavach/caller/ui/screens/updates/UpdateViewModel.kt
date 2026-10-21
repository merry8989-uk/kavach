package com.kavach.caller.ui.screens.updates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.update.UpdateInfo
import com.kavach.caller.data.update.UpdateInstaller
import com.kavach.caller.domain.usecase.CheckForUpdatesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val diagnostics: DiagnosticsRepository,
    private val checkForUpdates: CheckForUpdatesUseCase,
    private val installer: UpdateInstaller,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val current: String) : State
        data class Available(val info: UpdateInfo) : State
        data class Downloading(val percent: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun check(currentVersion: String) = viewModelScope.launch {
        _state.value = State.Checking
        val result = checkForUpdates(currentVersion)
        diagnostics.updateCheck(
            outcome = when (result) {
                is CheckForUpdatesUseCase.Result.Available -> "success"
                CheckForUpdatesUseCase.Result.UpToDate -> "no_op"
                CheckForUpdatesUseCase.Result.Unreachable -> "failed"
            },
            currentVersion = currentVersion,
            availableVersion = (result as? CheckForUpdatesUseCase.Result.Available)
                ?.info?.versionName,
            errorCode = if (result is CheckForUpdatesUseCase.Result.Unreachable) {
                "unreachable"
            } else {
                null
            },
        )
        _state.value = when (result) {
            is CheckForUpdatesUseCase.Result.Available -> State.Available(result.info)
            CheckForUpdatesUseCase.Result.UpToDate -> State.UpToDate(currentVersion)
            CheckForUpdatesUseCase.Result.Unreachable ->
                State.Error("Could not reach the update server. Check your connection.")
        }
    }

    fun downloadAndInstall(context: Context, info: UpdateInfo) = viewModelScope.launch {
        val url = info.apkUrl
        if (url == null) {
            _state.value = State.Error("This release has no APK attached. Open the release page instead.")
            return@launch
        }
        _state.value = State.Downloading(0)
        val file = installer.download(url) { percent -> _state.value = State.Downloading(percent) }
        if (file == null) {
            _state.value = State.Error("Download failed. Try again, or open the release page.")
            return@launch
        }
        _state.value = State.Available(info)
        installer.install(file)
    }

    /** Open the release page in a browser (fallback when install isn't wanted). */
    fun openReleasePage(context: Context, info: UpdateInfo) {
        runCatching {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(info.htmlUrl),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
