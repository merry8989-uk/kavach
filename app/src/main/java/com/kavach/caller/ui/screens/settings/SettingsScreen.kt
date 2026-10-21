package com.kavach.caller.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kavach.caller.ui.screens.updates.UpdateSection

/** Theme pack keys must match [com.kavach.caller.ui.theme.ThemePack] names. */
private val themePacks = listOf("AURORA", "CARBON", "DAYLIGHT", "NEON_NOIR", "DYNAMIC")
private val announcerModes = listOf("ALWAYS", "KNOWN_ONLY", "UNKNOWN_ONLY", "HEADSET_ONLY", "OFF")

@Composable
fun SettingsScreen(
    onOpenBlocklist: () -> Unit = {},
    onOpenRecordings: () -> Unit = {},
    onOpenFeatures: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenPolicy: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))

        SectionHeader("Modules")
        OutlinedButton(onClick = onOpenFeatures, modifier = Modifier.fillMaxWidth()) {
            Text("Features & modules")
        }
        Text(
            "Turn individual modules on or off, and see exactly why one can't run on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Protection")
        OutlinedButton(onClick = onOpenBlocklist, modifier = Modifier.fillMaxWidth()) {
            Text("Blocking rules & blocklist")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenRecordings, modifier = Modifier.fillMaxWidth()) {
            Text("Call recordings")
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Caller announcer (F7)")
        Text("Announcement mode", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            announcerModes.forEach { mode ->
                FilterChip(
                    selected = s.announcerMode == mode,
                    onClick = { vm.setAnnouncerMode(mode) },
                    label = { Text(mode.replace('_', ' ')) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        SwitchRow("Spoken spam verdict", s.announceSpamVerdict) { vm.setAnnounceSpamVerdict(it) }

        Spacer(Modifier.height(16.dp))
        SectionHeader("AI assistant (F10)")
        SwitchRow("Use cloud transcription & summaries", s.cloudAiEnabled) { vm.setCloudAiEnabled(it) }
        Text(
            "Cloud AI sends audio to Kavach's servers for transcription. Off by default for privacy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Recording (F8)")
        Text(
            "Recording is announced to callers and disabled automatically in two-party-consent regions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Appearance (F11)")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            themePacks.forEach { pack ->
                FilterChip(
                    selected = s.themePack == pack,
                    onClick = { vm.setThemePack(pack) },
                    label = { Text(pack.replace('_', ' ')) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Updates")
        Spacer(Modifier.height(8.dp))
        UpdateSection()

        Spacer(Modifier.height(16.dp))
        SectionHeader("Your identity")
        OutlinedButton(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
            Text("My number & consent")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenPolicy, modifier = Modifier.fillMaxWidth()) {
            Text("How Kavach identifies callers")
        }
        Text(
            "Verify your own number, choose whether your name is published, and read exactly " +
                "where caller names come from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Server (advanced)")
        var baseUrl by remember(s.backendBaseUrl) { mutableStateOf(s.backendBaseUrl) }
        var token by remember(s.backendToken) { mutableStateOf(s.backendToken) }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://your-kavach-server") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                vm.setBackendBaseUrl(baseUrl)
                vm.setBackendToken(token)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save server settings") }
        Text(
            "Kavach ships no hosted backend. Point it at your own deployment " +
                "(see backend/ in the repository) to turn on online caller lookup and reporting. " +
                "Leave empty to stay fully offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Troubleshooting")
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Diagnostics & logs")
        }
        Text(
            "Local technical log you can share to help fix a problem.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("About")
        Text("Kavach · armour for every call", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Feature requests & feedback: @osintgram_io",
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
