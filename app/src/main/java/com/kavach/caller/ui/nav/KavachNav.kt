package com.kavach.caller.ui.nav

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kavach.caller.ui.screens.assistant.AssistantScreen
import com.kavach.caller.ui.screens.blocklist.BlocklistScreen
import com.kavach.caller.ui.screens.calllog.CallLogScreen
import com.kavach.caller.ui.screens.contacts.ContactsScreen
import com.kavach.caller.ui.screens.dialer.DialerScreen
import com.kavach.caller.ui.screens.messages.MessagesScreen
import com.kavach.caller.ui.screens.onboarding.PermissionsScreen
import com.kavach.caller.ui.screens.onboarding.hasCorePermissions
import com.kavach.caller.ui.screens.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Material 3 recommends at most five bottom destinations, so Blocklist and the
// AI briefs inbox are reachable from within Settings and Calls instead of
// being tabs themselves.
private val tabs = listOf(
    Tab("dialer", "Dialer", Icons.Filled.Dialpad),
    Tab("contacts", "Contacts", Icons.Filled.People),
    Tab("calls", "Calls", Icons.Filled.Call),
    Tab("messages", "Messages", Icons.Filled.Sms),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun KavachNav() {
    val context = LocalContext.current
    // Gate the app behind the permission onboarding until the core permissions
    // are granted — otherwise every feature silently returns empty.
    var onboardingPassed by remember { mutableStateOf(hasCorePermissions(context)) }
    if (!onboardingPassed) {
        PermissionsScreen(onDone = { onboardingPassed = true })
        return
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
        NavHost(
            navController = nav,
            startDestination = "dialer",
            modifier = Modifier.padding(inner),
        ) {
            composable("dialer") { DialerScreen() }
            composable("contacts") { ContactsScreen() }
            composable("calls") {
                CallLogScreen(onOpenAssistant = { nav.navigate("assistant") })
            }
            composable("messages") { MessagesScreen() }
            composable("settings") {
                SettingsScreen(onOpenBlocklist = { nav.navigate("block") })
            }
            composable("block") { BlocklistScreen(onBack = { nav.popBackStack() }) }
            composable("assistant") { AssistantScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
