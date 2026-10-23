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
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import androidx.compose.material3.Switch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
    private val session: com.kavach.caller.data.diagnostics.SessionContext,
    private val secrets: com.kavach.caller.data.secure.SecretStore,
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

    var syncMessage by mutableStateOf<String?>(null)
        private set

    fun dismissSyncMessage() {
        syncMessage = null
    }

    var sizeText by mutableStateOf("0 events")
        private set

    init {
        // Bring across anything the old capped file held, then refresh. This runs
        // once per process; `importLegacyFile` renames the file so a second call
        // is a no-op, which also makes a partly-failed import safe to retry.
        viewModelScope.launch {
            repository.importLegacyFile()
            refresh()
        }
    }

    /**
     * How many events are actually stored.
     *
     * Separate from `entries.size`, and that separation is the fix. The screen
     * used to print `entries.size` and call it "recent entries" — so it said 400
     * forever, because 400 was the page size, and the real total was never shown.
     */
    var totalCount by mutableStateOf(0)
        private set

    var oldestText by mutableStateOf<String?>(null)
        private set

    var samplingEnabled by mutableStateOf(false)
        private set

    var retentionDays by mutableStateOf(0)
        private set

    /** True when the page is not the whole log, so the screen can say so. */
    val showingPartial: Boolean get() = totalCount > entries.value.size

    fun refresh() = viewModelScope.launch {
        _entries.value = repository.entries()
        _counts.value = repository.countsByLevel()
        totalCount = repository.totalCount()
        sizeText = repository.formattedSize(totalCount)
        samplingEnabled = settings.snapshot.diagnosticsSamplingEnabled
        retentionDays = settings.snapshot.diagnosticsRetentionDays
        oldestText = repository.oldestAt()?.let { stamp ->
            SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(stamp))
        }
    }

    fun loadMore() = viewModelScope.launch {
        // Pages forward through what is stored. There is no ceiling to reach.
        val next = repository.entries(limit = 200, offset = entries.value.size)
        if (next.isNotEmpty()) _entries.value = entries.value + next
    }

    fun setSampling(enabled: Boolean) = viewModelScope.launch {
        settings.setDiagnosticsSampling(enabled)
        refresh()
    }

    fun setRetention(days: Int) = viewModelScope.launch {
        settings.setDiagnosticsRetentionDays(days)
        // Applied at once, so the effect of the choice is visible rather than
        // waiting for the next event to trigger a sweep.
        repository.applyRetentionNow()
        refresh()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setDiagnosticsEnabled(enabled)
        refresh()
    }

    fun clear() = viewModelScope.launch {
        repository.clear()
        refresh()
    }

    suspend fun export(format: ExportFormat): File? = repository.export(format)

    fun emailBody(): String = repository.emailBody(totalCount)
}

/**
 * Diagnostics (F16.5). A local, opt-in technical log the user can read and share
 * to help fix problems on their device.
 *
 * Nothing is uploaded automatically, and numbers are masked before they are ever
 * written — see [DiagnosticsRepository].
 */
/** 0 is first because it is the default and the one worth choosing. */
private val retentionChoices = listOf(
    0 to "Never",
    30 to "30 days",
    90 to "90 days",
    365 to "1 year",
)

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
            Text(stringResource(R.string.diagnostics),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
            ) {
                item {
                    SectionHeader("This device")
                    KavachCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.user_code),
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
                                Text(stringResource(R.string.copy))
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
                                Text(stringResource(R.string.save_technical_logs),
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
                                    // The real total, not the page. This line
                                    // said "400 recent entries" for as long as
                                    // 400 was the page size.
                                    when {
                                        vm.totalCount == 0 -> "No events yet"
                                        vm.totalCount == 1 -> "1 event"
                                        else -> "${vm.totalCount} events"
                                    },
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    buildString {
                                        append(vm.sizeText)
                                        vm.oldestText?.let { append(" · oldest ").append(it) }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (vm.showingPartial) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { vm.loadMore() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Load ${vm.totalCount - entries.size} more")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        // Sends straight to the maintainer with the log attached,
                        // so nothing has to be typed or attached by hand.
                        Button(
                            onClick = {
                                scope.launch {
                                    // The export is what produces a file now; the
                                    // store is a database, so there is nothing to
                                    // attach until one is written.
                                    emailLog(context, vm.export(ExportFormat.TXT), vm.emailBody())
                                }
                            },
                            enabled = vm.totalCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Email to $DEVELOPER_EMAIL") }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showFormats = true },
                            enabled = entries.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.export_as)) }

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        vm.export(ExportFormat.TXT)?.let { shareLog(context, it) }
                                    }
                                },
                                enabled = vm.totalCount > 0,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.share)) }
                            OutlinedButton(
                                onClick = { vm.clear() },
                                enabled = entries.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.clear)) }
                            OutlinedButton(
                                onClick = { vm.refresh() },
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.refresh)) }
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

                    Spacer(Modifier.height(16.dp))
                    SectionHeader("Collection")
                    KavachCard {
                        // The spec asks for 100% of eligible events, and the
                        // shipped default is exactly that. Saying so before
                        // offering the switch is the point: a user who never
                        // changes anything should know nothing is being thrown
                        // away.
                        Text(
                            if (vm.samplingEnabled) {
                                "Sampling on. Above a busy threshold, routine events are thinned out."
                            } else {
                                "Keeping everything. Every event is stored until you delete it — " +
                                    "no sampling, no size ceiling, no automatic expiry."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Thin out routine events", color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "Only applies to routine events. Errors, warnings and crashes are never sampled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = vm.samplingEnabled,
                                onCheckedChange = { vm.setSampling(it) },
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Text("Delete events older than", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            retentionChoices.forEach { (days, label) ->
                                FilterChip(
                                    selected = vm.retentionDays == days,
                                    onClick = { vm.setRetention(days) },
                                    label = { Text(label) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (vm.retentionDays == 0) {
                                "Never. Nothing is deleted automatically — the log only shrinks when you clear or export it."
                            } else {
                                "Events older than ${vm.retentionDays} days are removed. " +
                                    "The oldest kept event is ${vm.oldestText ?: "not yet known"}."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
        // A fatal is the failure that ended the process, so it reads louder than
        // an error rather than the same as one.
        LogLevel.FATAL -> MaterialTheme.colorScheme.error
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.TRACE -> MaterialTheme.colorScheme.onSurfaceVariant
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
        title = { Text(stringResource(R.string.export_as_2)) },
        text = {
            Column {
                Text(stringResource(R.string.pick_a_format_only_formats_something_can_actually_op),
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
