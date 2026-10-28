package com.kavach.caller

import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.kavach.caller.domain.DefaultAppStatus
import com.kavach.caller.domain.DefaultRoleNudge

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Apply the chosen language before anything else is built.
     *
     * Doing it here rather than in `onCreate` means every screen — including the
     * ones the system recreates — resolves its strings against the right
     * resources, with no per-composable work and no chance of a screen being
     * drawn in the wrong language first.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.kavach.caller.i18n.LanguageStore.wrap(newBase))
    }

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
                // Asked on open, because this is the moment the user is looking at
                // the app and can act on it. It fires only when the rules say so —
                // see DefaultRoleNudge — and never for someone who already set it.
                DefaultRolePrompt(vm)
                // "Was that call spam?" for a call that ended since the last open.
                com.kavach.caller.ui.screens.community.SpamPromptDialog()
            }
        }
    }
}


/**
 * The "set Kavach as default" dialog, on app open.
 *
 * Only for someone who said "Later" and whose two days are up. Someone who has
 * never been asked is setup's business, and someone who already set it is not
 * asked again — a prompt for something already done is how people learn to
 * dismiss prompts without reading them.
 */
@Composable
private fun DefaultRolePrompt(vm: SettingsViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var isDefault by remember { mutableStateOf(DefaultAppStatus.isDefault(context)) }
    var asked by remember { mutableStateOf(false) }
    var launcherFor by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isDefault = DefaultAppStatus.isDefault(context)
        if (isDefault) vm.clearDefaultNudge()
    }

    LaunchedEffect(launcherFor) {
        val role = launcherFor ?: return@LaunchedEffect
        DefaultAppStatus.requestIntent(context, role)?.let { launcher.launch(it) }
        launcherFor = null
    }

    // `asked` stops it reappearing in the same session after a dismissal.
    val shouldAsk = !asked && DefaultRoleNudge.shouldAsk(
        isDefault = isDefault,
        scheduledAt = settings.defaultNudgeAt,
        now = System.currentTimeMillis(),
    )
    if (!shouldAsk) return

    val missing = DefaultAppStatus.missing(context)
    AlertDialog(
        onDismissRequest = {
            asked = true
            vm.setDefaultLater()
        },
        title = { Text("Make Kavach your default dialer?") },
        text = {
            Text(
                missing.joinToString(" and ") { it.label } +
                    " is still not set. Until it is, the in-call screen, caller " +
                    "identification and call recording cannot work.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                asked = true
                launcherFor = missing.firstOrNull()?.id
            }) { Text("Set as default") }
        },
        dismissButton = {
            TextButton(onClick = {
                asked = true
                // Answered, so the next ask is two days out rather than tomorrow.
                vm.setDefaultLater()
            }) { Text("Later") }
        },
    )
}
