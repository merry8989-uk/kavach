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

@Composable
fun SettingsScreen() {
    var announcerOn by remember { mutableStateOf(true) }
    var aiOn by remember { mutableStateOf(true) }
    var cloudAi by remember { mutableStateOf(false) }
    var recordAll by remember { mutableStateOf(false) }
    var themePack by remember { mutableStateOf("Aurora") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))

        SectionHeader("Caller announcer (F7)")
        SwitchRow("Announce incoming callers", announcerOn) { announcerOn = it }
        SwitchRow("Spoken spam verdict", announcerOn) { }

        Spacer(Modifier.height(16.dp))
        SectionHeader("AI assistant (F10)")
        SwitchRow("Answer unknown calls with AI", aiOn) { aiOn = it }
        SwitchRow("Use cloud transcription & summaries", cloudAi) { cloudAi = it }
        Text(
            "Cloud AI sends audio to Kavach's servers for transcription. Off by default for privacy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Recording (F8)")
        SwitchRow("Record all calls", recordAll) { recordAll = it }
        Text(
            "Recording is announced to callers and disabled automatically in two-party-consent regions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Appearance (F11)")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("Aurora", "Carbon", "Daylight", "Neon Noir", "Dynamic").forEach { pack ->
                FilterChip(
                    selected = themePack == pack,
                    onClick = { themePack = pack },
                    label = { Text(pack) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

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
