package com.kavach.caller.ui.screens.diagnostics

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext as LocalCtx
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
import com.kavach.caller.BuildConfig
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.diagnostics.EventSchema
import com.kavach.caller.data.diagnostics.ExportFormat
import com.kavach.caller.data.diagnostics.LogEvent
import com.kavach.caller.data.diagnostics.LogLevel
import com.kavach.caller.data.notion.NotionSyncRepository
import com.kavach.caller.data.notion.SecretStore
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
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
    private val session: com.kavach.caller.data.diagnostics.SessionContext,
    private val notionSync: com.kavach.caller.data.notion.NotionSyncRepository,
    private val secrets: com.kavach.caller.data.notion.SecretStore,
    private val settings: com.kavach.caller.data.settings.SettingsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val appSettings = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _entries = MutableStateFlow<List<LogEvent>>(emptyList())
    val entries: StateFlow<List<LogEvent>> = _entries.asStateFlow()

    private val _counts = MutableStateFlow<Map<LogLevel, Int>>(emptyMap())
    val counts: StateFlow<Map<LogLevel, Int>> = _counts.asStateFlow()

    val schemaVersion: String get() = EventSchema.VERSION

    /** The readable per-install code, shown so a tester can read it out. */
    val userCode: String get() = session.userCode

    private val _notion = MutableStateFlow<NotionSyncRepository.Status?>(null)
    val notion: StateFlow<NotionSyncRepository.Status?> = _notion.asStateFlow()

    var syncMessage by mutableStateOf<String?>(null)
        private set

    fun dismissSyncMessage() {
        syncMessage = null
    }

    fun refreshNotion() = viewModelScope.launch {
        _notion.value = notionSync.status()
    }

    fun setNotionToken(token: String) = viewModelScope.launch {
        secrets.put(SecretStore.NOTION_TOKEN, token.trim())
        syncMessage = if (token.isBlank()) "Notion token cleared." else "Notion token saved."
        refreshNotion()
    }

    fun setNotionEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setNotionSyncEnabled(enabled)
        syncMessage = if (enabled) {
            "Automatic sync on. Events will upload as they happen."
        } else {
            "Automatic sync off. Nothing leaves this phone."
        }
        refreshNotion()
    }

    fun setNotionWifiOnly(wifiOnly: Boolean) = viewModelScope.launch {
        settings.setNotionWifiOnly(wifiOnly)
        refreshNotion()
    }

    fun syncNow() = viewModelScope.launch {
        syncMessage = "Syncing…"
        val (sent, failure) = notionSync.flush()
        syncMessage = when {
            failure != null && sent == 0 -> "Could not sync: $failure"
            sent == 0 -> "Nothing to sync."
            else -> "Sent $sent event(s)."
        }
        refreshNotion()
    }

    fun clearNotionQueue() = viewModelScope.launch {
        notionSync.clearQueue()
        syncMessage = "Upload queue cleared."
        refreshNotion()
    }

    var sizeText by mutableStateOf("0 B")
        private set

    fun refresh() = viewModelScope.launch {
        _entries.value = repository.entries()
        _counts.value = repository.countsByLevel()
        sizeText = repository.formattedSize()
        _notion.value = notionSync.status()
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

    suspend fun export(format: ExportFormat): File? = repository.export(format)

    fun emailBody(): String = repository.emailBody(entries.value.size)
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
    val scope = rememberCoroutineScope()

    var showFormats by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    // One launcher, generic MIME: the chosen format decides the file name and
    // extension, which is what the receiving app keys off.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            true
        }.getOrDefault(false)
        exportMessage = if (ok) "Exported ${file.name}" else "Could not write the file."
    }

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
                    SectionHeader("Notion dashboard")
                    val notion = vm.notion.collectAsState().value
                    var tokenDraft by remember { mutableStateOf("") }
                    var showToken by remember { mutableStateOf(false) }

                    KavachCard {
                        Text(
                            "Send events straight to the Kavach Diagnostics — Events database " +
                                "in Notion, so the weekly review fills itself.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Automatic sync", color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    if (notion?.hasToken == true) {
                                        "Token saved on this device, encrypted by the Keystore."
                                    } else {
                                        "Paste a Notion integration token below to turn this on."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = notion?.enabled == true,
                                enabled = notion?.hasToken == true,
                                onCheckedChange = { vm.setNotionEnabled(it) },
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = tokenDraft,
                            onValueChange = { tokenDraft = it },
                            label = { Text("Notion integration token") },
                            placeholder = { Text("ntn_… or secret_…") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showToken) {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            } else {
                                androidx.compose.ui.text.input.PasswordVisualTransformation()
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.setNotionToken(tokenDraft); tokenDraft = "" },
                                enabled = tokenDraft.isNotBlank(),
                            ) { Text("Save token") }
                            OutlinedButton(onClick = { showToken = !showToken }) {
                                Text(if (showToken) "Hide" else "Show")
                            }
                            if (notion?.hasToken == true) {
                                TextButton(onClick = { vm.setNotionToken("") }) { Text("Clear") }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Wi-Fi only", color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "Keeps uploads off a metered plan.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = notion?.wifiOnly == true,
                                onCheckedChange = { vm.setNotionWifiOnly(it) },
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            buildString {
                                append("Waiting to upload: ${notion?.pending ?: 0}")
                                append("  ·  Uploaded: ${notion?.syncedTotal ?: 0}")
                                notion?.lastSyncAt?.let {
                                    append("  ·  Last: ")
                                    append(
                                        android.text.format.DateUtils.getRelativeTimeSpanString(it),
                                    )
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        notion?.lastError?.let { err ->
                            Text(
                                "Last problem: $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.syncNow() }, enabled = notion?.enabled == true) {
                                Text("Sync now")
                            }
                            OutlinedButton(
                                onClick = { vm.clearNotionQueue() },
                                enabled = (notion?.pending ?: 0) > 0,
                            ) { Text("Clear queue") }
                        }

                        vm.syncMessage?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    msg,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                TextButton(onClick = { vm.dismissSyncMessage() }) { Text("OK") }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Setup: in Notion, create an internal integration, give it access to " +
                                "the Kavach Diagnostics page, copy its token, and paste it here. " +
                                "The token stays on this phone — Kavach ships no Notion credentials, " +
                                "and nothing is uploaded until you switch this on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    SectionHeader("This device")
                    KavachCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "User code",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    vm.userCode,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "A random per-install pseudonym. Quote it when reporting an " +
                                        "issue so the rows from this phone group together. It is " +
                                        "not personal data, and reinstalling changes it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { copyUserCode(context, vm.userCode) }) {
                                Text("Copy")
                            }
                        }
                    }
                }

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
                        // Sends straight to the maintainer with the log attached,
                        // so nothing has to be typed or attached by hand.
                        Button(
                            onClick = { emailLog(context, vm.logFile(), vm.emailBody()) },
                            enabled = entries.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Email to $DEVELOPER_EMAIL") }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showFormats = true },
                            enabled = entries.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export as…") }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
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

                        exportMessage?.let { text ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
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

    ExportFormatDialogHost(
        visible = showFormats,
        onDismiss = { showFormats = false },
        onPick = { format ->
            showFormats = false
            scope.launch {
                val file = vm.export(format)
                if (file == null) {
                    exportMessage = "Nothing to export yet."
                } else {
                    pendingExport = file
                    // The system save dialog is where the user picks the folder
                    // and confirms the file name and extension.
                    saveLauncher.launch(file.name)
                }
            }
        },
    )
}

@Composable
private fun LogRow(event: LogEvent) {
    val colour = when (event.logLevel) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timestampMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                event.logLevel.name,
                style = MaterialTheme.typography.labelSmall,
                color = colour,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                event.eventName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (event.properties.isNotEmpty()) {
            Text(
                event.properties.entries.joinToString("  ") { "${it.key}=${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (event.attemptId != null) {
            Text(
                "attempt ${event.attemptId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
}

@Composable
private fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onPick: (ExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export as") },
        text = {
            Column {
                Text(
                    "Pick a format. Only formats something can actually open are offered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(ExportFormat.entries.toList()) { format ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(format) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(format.label, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                format.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Compose a mail to the maintainer with the log already attached and the
 * device details already written. The user only has to describe the problem.
 */
private fun copyUserCode(context: Context, code: String) {
    val manager = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("Kavach user code", code))
}

private fun emailLog(context: Context, file: File?, body: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Kavach diagnostics — ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, body)
            if (file != null) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Email diagnostics"))
    }
}

private const val DEVELOPER_EMAIL = "br19shc39@gmail.com"

@Composable
private fun ExportFormatDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (ExportFormat) -> Unit,
) {
    if (visible) ExportFormatDialog(onDismiss = onDismiss, onPick = onPick)
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
