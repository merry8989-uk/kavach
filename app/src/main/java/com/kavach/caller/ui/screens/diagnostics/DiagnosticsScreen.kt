package com.kavach.caller.ui.screens.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.diagnostics.LogEntry
import com.kavach.caller.data.diagnostics.LogLevel
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val appSettings = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    var sizeText by mutableStateOf("0 B")
        private set

    fun refresh() = viewModelScope.launch {
        _entries.value = repository.entries()
        sizeText = repository.formattedSize()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setDiagnosticsEnabled(enabled)
        refresh()
    }

    fun clear() = viewModelScope.launch {
        repository.clear()
        refresh()
    }

    fun logFile(): File? = repository.logFile()
}

/**
 * Diagnostics (F16.5). A local, opt-in technical log the user can read and share
 * to help fix problems on their device.
 *
 * Nothing is uploaded automatically, and numbers are masked before they are ever
 * written — see [DiagnosticsRepository].
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    vm: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by vm.appSettings.collectAsState()
    val entries by vm.entries.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Diagnostics",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                item {
                    SectionHeader("Collection")
                    KavachCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Save technical logs",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "Records what the app did so a bug can be diagnosed. " +
                                        "Stored only on this phone.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.diagnosticsEnabled,
                                onCheckedChange = { vm.setEnabled(it) },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Phone numbers are masked to their last 4 digits before being written. " +
                                "Contact names, message contents and call audio are never logged.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    SectionHeader("Log")
                    KavachCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${entries.size} recent entries",
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "File size ${vm.sizeText}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.logFile()?.let { shareLog(context, it) } },
                                enabled = vm.logFile() != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("Share") }
                            OutlinedButton(
                                onClick = { vm.clear() },
                                enabled = entries.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text("Clear") }
                            OutlinedButton(
                                onClick = { vm.refresh() },
                                modifier = Modifier.weight(1f),
                            ) { Text("Refresh") }
                        }
                    }
                }

                if (entries.isEmpty()) {
                    item {
                        SectionHeader("Entries")
                        KavachCard {
                            Text(
                                if (settings.diagnosticsEnabled) {
                                    "Nothing logged yet. Use the app for a bit and come back."
                                } else {
                                    "Collection is off, so nothing is being recorded."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item { SectionHeader("Entries") }
                    items(entries) { entry ->
                        LogRow(entry)
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val colour = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.level.name,
                style = MaterialTheme.typography.labelSmall,
                color = colour,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
}

private fun shareLog(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Kavach diagnostics log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share diagnostics log"))
    }
}
