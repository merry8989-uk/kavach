package com.kavach.caller.ui.screens.recordings

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.RecordingEntity
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.recording.RecordingCapability
import com.kavach.caller.recording.RecordingStore
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val store: RecordingStore,
    private val capability: RecordingCapability,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<RecordingEntity>>(emptyList())
    val items: StateFlow<List<RecordingEntity>> = _items.asStateFlow()

    private val _usage = MutableStateFlow(Triple(0L, 0, RecordingStore.MAX_BYTES))
    val usage: StateFlow<Triple<Long, Int, Long>> = _usage.asStateFlow()

    /** Assessed once per visit; the device does not change mid-session. */
    val report: RecordingCapability.Report by lazy { capability.assess() }

    val consentGiven: Boolean get() = settings.snapshot.recordingConsentGiven

    var message by mutableStateOf<String?>(null)
        private set

    fun dismissMessage() {
        message = null
    }

    fun clearMessage() {
        message = null
    }

    fun showMessage(text: String) {
        message = text
    }

    fun refresh() = viewModelScope.launch {
        _items.value = store.all()
        _usage.value = store.usage()
    }

    fun giveConsent() = viewModelScope.launch {
        settings.setRecordingConsent(true)
        message = "Recording enabled. Kavach shows an indicator and announces before it starts."
        refresh()
    }

    fun withdrawConsent() = viewModelScope.launch {
        settings.setRecordingConsent(false)
        message = "Recording turned off. Existing recordings are still here for you to review."
        refresh()
    }

    fun delete(entity: RecordingEntity) = viewModelScope.launch {
        store.delete(entity)
        message = "Recording deleted."
        refresh()
    }

    /** Decrypt to a temporary file the player can open. */
    suspend fun playbackSource(entity: RecordingEntity) = store.playbackFile(entity)

    fun releasePlayback() = store.discardPlayback()

    fun trimNow() = viewModelScope.launch {
        val removed = store.enforceQuota()
        message = if (removed == 0) "Nothing to trim." else "Removed $removed old recording(s)."
        refresh()
    }
}

/**
 * Recordings, and the honest assessment behind them (spec Phase 9).
 *
 * Three things this screen is careful about:
 *  - it will not enable recording until the user has explicitly agreed;
 *  - it states what this device can actually capture, rather than implying both
 *    sides are recorded;
 *  - it shows the encryption state of each file, because the previous version
 *    claimed encryption in the data model without doing any.
 */
@Composable
fun RecordingsScreen(
    onBack: () -> Unit = {},
    vm: RecordingsViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()
    val usage by vm.usage.collectAsState()
    var playingId by remember { mutableStateOf<Long?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.refresh() }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            vm.releasePlayback()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(stringResource(R.string.recordings),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            vm.message?.let { text ->
                item {
                    KavachCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = { vm.dismissMessage() }) { Text(stringResource(R.string.ok)) }
                        }
                    }
                }
            }

            // ---- consent ----
            item {
                SectionHeader("Consent")
                KavachCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (vm.consentGiven) "Recording is on" else "Recording is off",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Kavach never records silently. When recording is on it shows a " +
                                    "permanent notification for the whole call.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = vm.consentGiven,
                            onCheckedChange = { on ->
                                if (on) vm.giveConsent() else vm.withdrawConsent()
                            },
                        )
                    }
                }
            }

            // ---- what this device can actually do ----
            item {
                SectionHeader("What this device can record")
                KavachCard {
                    Text(
                        vm.report.headline,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        vm.report.explanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    CapabilityRow("Microphone permission", vm.report.hasMicPermission)
                    CapabilityRow("Default dialer role", vm.report.hasDialerRole)
                    CapabilityRow("Encrypted storage", vm.report.encryptionAvailable)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        vm.report.legalNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (vm.report.capture == RecordingCapability.Capture.MICROPHONE) {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.if_recording_is_not_enough_you_can),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        vm.report.alternatives.forEach { line ->
                            Text(
                                "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---- storage ----
            item {
                SectionHeader("Storage")
                KavachCard {
                    val (bytes, count, cap) = usage
                    val fraction = if (cap > 0) (bytes.toFloat() / cap).coerceIn(0f, 1f) else 0f
                    Text(
                        "${formatBytes(bytes)} of ${formatBytes(cap)} used · $count recording(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Oldest recordings are removed automatically once the limit is reached, " +
                            "so a full disk cannot stop a recording mid-call.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.trimNow() }) { Text(stringResource(R.string.free_up_space_now)) }
                }
            }

            if (items.isEmpty()) {
                item {
                    EmptyState(
                        title = "No recordings yet",
                        subtitle = if (vm.consentGiven) {
                            "Calls you record will appear here, encrypted."
                        } else {
                            "Turn recording on above to start."
                        },
                        action = "Refresh",
                        onAction = { vm.refresh() },
                    )
                }
            } else {
                item { SectionHeader("Saved recordings") }
                items(items, key = { it.id }) { recording ->
                    KavachCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (playingId == recording.id) {
                                        runCatching { player?.stop() }
                                        runCatching { player?.release() }
                                        player = null
                                        playingId = null
                                        vm.releasePlayback()
                                    } else {
                                        vm.clearMessage()
                                        // Decrypting is I/O, so it happens off the main thread.
                                        scope.launch {
                                            val source = vm.playbackSource(recording)
                                            if (source == null) {
                                                vm.showMessage(
                                                    "Could not open this recording — the file is missing.",
                                                )
                                                return@launch
                                            }
                                            runCatching {
                                                player?.release()
                                                player = MediaPlayer().apply {
                                                    setDataSource(source.absolutePath)
                                                    prepare()
                                                    start()
                                                }
                                                playingId = recording.id
                                            }.onFailure {
                                                vm.showMessage("Could not play this recording.")
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    if (playingId == recording.id) Icons.Filled.Stop
                                    else Icons.Filled.PlayArrow,
                                    contentDescription = if (playingId == recording.id) "Stop" else "Play",
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    PhoneUtils.pretty(recording.number),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    buildString {
                                        append(formatDate(recording.timestamp))
                                        if (recording.durationSec > 0) {
                                            append(" · ")
                                            append(formatDuration(recording.durationSec))
                                        }
                                        append(" · ")
                                        append(formatBytes(recording.sizeBytes))
                                        append(" · ")
                                        append(if (recording.encrypted) "Encrypted" else "Not encrypted")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (recording.encrypted) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            IconButton(onClick = { vm.delete(recording) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CapabilityRow(label: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    shape = MaterialTheme.shapes.small,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            if (ok) "yes" else "no",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
