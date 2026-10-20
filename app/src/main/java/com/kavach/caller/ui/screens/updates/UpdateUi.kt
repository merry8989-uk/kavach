package com.kavach.caller.ui.screens.updates

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kavach.caller.BuildConfig

/** The "check for updates" control, embedded in Settings. */
@Composable
fun UpdateSection(vm: UpdateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Installed version: ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        when (val s = state) {
            is UpdateViewModel.State.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking for updates…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            is UpdateViewModel.State.UpToDate -> {
                Text("You're on the latest version.", color = MaterialTheme.colorScheme.primary)
            }

            is UpdateViewModel.State.Available -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Kavach ${s.info.versionName} is available",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (s.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                s.info.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Button(onClick = { vm.downloadAndInstall(context, s.info) }) {
                                Text("Download & install")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.openReleasePage(context, s.info) }) {
                                Text("Release notes")
                            }
                        }
                    }
                }
            }

            is UpdateViewModel.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("Downloading… ${s.percent}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is UpdateViewModel.State.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
            }

            UpdateViewModel.State.Idle -> Unit
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.check(BuildConfig.VERSION_NAME) },
            enabled = state !is UpdateViewModel.State.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check for updates")
        }
    }
}

/** Slim banner shown at the top of the app when an update is waiting. */
@Composable
fun UpdateBanner(version: String, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Kavach $version is available",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpen) { Text("Update") }
        }
    }
}
