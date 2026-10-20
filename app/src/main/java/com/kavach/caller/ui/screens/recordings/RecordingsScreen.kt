package com.kavach.caller.ui.screens.recordings

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.RecordingEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    repo: CallLogRepository,
) : ViewModel() {
    val recordings = repo.observeRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Playback for the recordings the assistant and the recorder produce (F8.5). */
@Composable
fun RecordingsScreen(
    onBack: () -> Unit = {},
    vm: RecordingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val items by vm.recordings.collectAsState()
    var playingId by remember { mutableStateOf<Long?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    fun toggle(recording: RecordingEntity) {
        if (playingId == recording.id) {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            playingId = null
            return
        }
        runCatching { player?.release() }
        val mp = MediaPlayer()
        val ok = runCatching {
            mp.setDataSource(recording.filePath)
            mp.prepare()
            mp.start()
        }.isSuccess
        if (ok) {
            mp.setOnCompletionListener {
                runCatching { it.release() }
                player = null
                playingId = null
            }
            player = mp
            playingId = recording.id
        } else {
            runCatching { mp.release() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Call recordings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        if (items.isEmpty()) {
            EmptyState(
                title = "No recordings yet",
                subtitle = "Turn on recording, or let the AI assistant handle a call, and they appear here.",
                action = "Got it",
                onAction = {},
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(PhoneUtils.pretty(r.number), fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground)
                            Text(
                                "${formatDate(r.timestamp)} · ${formatSize(r.sizeBytes)}" +
                                    if (r.encrypted) " · encrypted" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { toggle(r) }) {
                            Icon(
                                if (playingId == r.id) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (playingId == r.id) "Stop" else "Play",
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale("en", "IN")).format(Date(ts))

private fun formatSize(bytes: Long): String =
    if (bytes <= 0) "—" else "%.1f MB".format(bytes / 1_048_576.0)
