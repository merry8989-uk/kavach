package com.kavach.caller.ui.screens.messages

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.ui.theme.KavachTheme
import com.kavach.caller.ui.theme.ThemePack
import com.kavach.caller.ui.theme.themePackFromKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesThemeViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ThemePack.AURORA to "system")
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { snapshot ->
                _state.value = themePackFromKey(snapshot.themePack) to snapshot.smsTheme
            }
        }
    }
}

/**
 * The messaging theme.
 *
 * Kavach's themes are **packs** — Aurora, Carbon, Daylight and so on — rather than
 * a light/dark switch, and that is what the rest of the app offers. This maps the
 * messaging setting onto them instead of inventing a second scheme:
 *
 * - **Light** uses the Daylight pack.
 * - **Dark** uses whichever pack the app is set to. Every pack except Daylight is
 *   dark-first, so this is the honest reading of "dark" here.
 * - **System** follows the device: Daylight when the system is in light mode,
 *   otherwise the app's pack.
 *
 * The alternative was a light/dark switch that ignored the app's own theme, which
 * would mean two themes fighting over the same screens.
 */
@Composable
fun MessagesTheme(
    content: @Composable () -> Unit,
    vm: MessagesThemeViewModel = hiltViewModel(),
) {
    val (appPack, mode) by vm.state.collectAsState()
    val systemIsDark = isSystemInDarkTheme()

    // The pack's own `isLight` decides, rather than a second list of which packs
    // are light — that list would go stale the moment a pack is added.
    val darkPack = if (appPack.isLight) ThemePack.AURORA else appPack
    val pack = when (mode) {
        "light" -> ThemePack.DAYLIGHT
        "dark" -> darkPack
        else -> if (systemIsDark) darkPack else ThemePack.DAYLIGHT
    }

    KavachTheme(pack = pack) { content() }
}
