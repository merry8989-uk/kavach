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
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground)
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
        SwitchRow("Answer unknown calls with AI", s.aiEnabled) { vm.setAiEnabled(it) }
        SwitchRow("Use cloud transcription & summaries", s.cloudAiEnabled) { vm.setCloudAiEnabled(it) }
        Text(
            "Cloud AI sends audio to Kavach's servers for transcription. Off by default for privacy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Recording (F8)")
        SwitchRow("Record all calls", s.recordAll) { vm.setRecordAll(it) }
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
        SwitchRow("Check for updates on launch", s.autoCheckUpdates) { vm.setAutoCheckUpdates(it) }
        Spacer(Modifier.height(8.dp))
        UpdateSection()

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
