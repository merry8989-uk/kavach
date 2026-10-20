package com.kavach.caller.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kavach.caller.BuildConfig
import com.kavach.caller.ui.screens.assistant.AssistantScreen
import com.kavach.caller.ui.screens.blocklist.BlocklistScreen
import com.kavach.caller.ui.screens.calllog.CallLogScreen
import com.kavach.caller.ui.screens.contacts.ContactsScreen
import com.kavach.caller.ui.screens.dialer.DialerScreen
import com.kavach.caller.ui.screens.messages.MessagesScreen
import com.kavach.caller.ui.screens.onboarding.PermissionsScreen
import com.kavach.caller.ui.screens.onboarding.hasCorePermissions
import com.kavach.caller.ui.screens.recordings.RecordingsScreen
import com.kavach.caller.ui.screens.settings.SettingsScreen
import com.kavach.caller.ui.screens.settings.SettingsViewModel
import com.kavach.caller.ui.screens.updates.UpdateBanner
import com.kavach.caller.ui.screens.updates.UpdateViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Material 3 recommends at most five bottom destinations, so Blocklist, the AI
// briefs inbox and Recordings are reachable from within Settings and Calls
// instead of being tabs themselves.
private val tabs = listOf(
    Tab("dialer", "Dialer", Icons.Filled.Dialpad),
    Tab("contacts", "Contacts", Icons.Filled.People),
    Tab("calls", "Calls", Icons.Filled.Call),
    Tab("messages", "Messages", Icons.Filled.Sms),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

/**
 * Entry point. Shows the first-run setup until it has been completed (or
 * skipped) *and* the core permissions are held — then hands off to [KavachMain].
 */
@Composable
fun KavachNav() {
    val context = LocalContext.current
    val settingsVm: SettingsViewModel = hiltViewModel()
    val appSettings by settingsVm.settings.collectAsState()
    val settingsLoaded by settingsVm.loaded.collectAsState()
    var setupSkipped by remember { mutableStateOf(false) }

    // Wait for persisted settings before deciding, so the setup screen never
    // flashes on a launch where it has already been done.
    if (!settingsLoaded) {
        Box(Modifier.fillMaxSize())
        return
    }

    val needsSetup = !appSettings.onboardingDone || !hasCorePermissions(context)
    if (needsSetup && !setupSkipped) {
        PermissionsScreen(
            onFinished = {
                settingsVm.setOnboardingDone(true)
                setupSkipped = true
            },
            onSkip = { setupSkipped = true },
        )
        return
    }

    KavachMain(settingsVm)
}

@Composable
private fun KavachMain(settingsVm: SettingsViewModel) {
    val appSettings by settingsVm.settings.collectAsState()
    val updateVm: UpdateViewModel = hiltViewModel()
    val updateState by updateVm.state.collectAsState()

    // Optional launch-time update check (toggle in Settings).
    LaunchedEffect(Unit) {
        if (appSettings.autoCheckUpdates) {
            updateVm.check(BuildConfig.VERSION_NAME)
        }
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            val state = updateState
            if (state is UpdateViewModel.State.Available) {
                UpdateBanner(
                    version = state.info.versionName,
                    onOpen = { nav.navigate("settings") },
                )
            }
            NavHost(
                navController = nav,
                startDestination = "dialer",
                modifier = Modifier.weight(1f),
            ) {
                composable("dialer") { DialerScreen() }
                composable("contacts") { ContactsScreen() }
                composable("calls") {
                    CallLogScreen(onOpenAssistant = { nav.navigate("assistant") })
                }
                composable("messages") { MessagesScreen() }
                composable("settings") {
                    SettingsScreen(
                        onOpenBlocklist = { nav.navigate("block") },
                        onOpenRecordings = { nav.navigate("recordings") },
                    )
                }
                composable("block") { BlocklistScreen(onBack = { nav.popBackStack() }) }
                composable("assistant") { AssistantScreen(onBack = { nav.popBackStack() }) }
                composable("recordings") { RecordingsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
