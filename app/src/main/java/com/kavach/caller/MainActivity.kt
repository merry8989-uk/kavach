package com.kavach.caller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.kavach.caller.ui.nav.KavachNav
import com.kavach.caller.ui.screens.settings.SettingsViewModel
import com.kavach.caller.ui.theme.KavachTheme
import com.kavach.caller.ui.theme.themePackFromKey
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The theme pack now comes from persisted settings, so the switcher
            // in Settings actually recolours the whole app.
            val vm: SettingsViewModel = hiltViewModel()
            val settings by vm.settings.collectAsState()
            KavachTheme(pack = themePackFromKey(settings.themePack)) {
                KavachNav()
            }
        }
    }
}
