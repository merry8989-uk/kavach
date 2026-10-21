package com.kavach.caller.ui.screens.calllog

import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.ui.components.CallerLookupDialog
import com.kavach.caller.ui.components.SectionHeader
import com.kavach.caller.ui.screens.contacts.Avatar
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CallLogViewModel @Inject constructor(
    private val repo: CallLogRepository,
) : ViewModel() {
    val entries = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var filter by mutableStateOf(Filter.ALL)
    var visibleCount by mutableStateOf(PAGE_SIZE)

    fun sync() = viewModelScope.launch { repo.syncFromSystem() }

    fun showMore() {
        visibleCount += PAGE_SIZE
    }

    enum class Filter(val label: String) {
        ALL("All"), MISSED("Missed"), SPAM("Spam"), AI("AI answered"), RECORDED("Recorded")
    }

    /** Filtered but not truncated — lets the UI know whether more remain. */
    fun matching(all: List<CallLogEntity>): List<CallLogEntity> = when (filter) {
        Filter.ALL -> all
        Filter.MISSED -> all.filter { it.type == CallLog.Calls.MISSED_TYPE }
        Filter.SPAM -> all.filter { it.spamScore >= 50 }
        Filter.AI -> all.filter { it.aiAnswered }
        Filter.RECORDED -> all.filter { it.recordingId != null }
    }

    fun apply(all: List<CallLogEntity>): List<CallLogEntity> =
        matching(all).take(visibleCount)

    companion object {
        const val PAGE_SIZE = 40
    }
}

@Composable
fun CallLogScreen(
    onOpenAssistant: () -> Unit = {},
    vm: CallLogViewModel = hiltViewModel(),
) {
    val all by vm.entries.collectAsState()
    val matching = vm.matching(all)
    val filtered = matching.take(vm.visibleCount)
    var lookupNumber by remember { mutableStateOf<String?>(null) }

    // Group into Today / Yesterday / Older so a long history stays readable.
    val grouped: Map<String, List<CallLogEntity>> = filtered.groupBy { dayBucket(it.timestamp) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Call history",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onOpenAssistant) { Text("AI briefs") }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            CallLogViewModel.Filter.entries.forEach { f ->
                FilterChip(
                    selected = vm.filter == f,
                    onClick = { vm.filter = f; vm.visibleCount = CallLogViewModel.PAGE_SIZE },
                    label = { Text(f.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        if (all.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.PhoneInTalk,
                title = "No call history",
                subtitle = "Import your device call log to see it enriched with names and spam scores.",
                action = "Import call log",
                onAction = { vm.sync() },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (bucket, items) ->
                    item(key = "header-$bucket") { SectionHeader(bucket) }
                    items(items, key = { it.id }) { entry ->
                        CallRow(entry, onLongPress = { lookupNumber = entry.number })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                        )
                    }
                }
                if (filtered.size < matching.size) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = { vm.showMore() }) { Text("Load more") }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    lookupNumber?.let { number ->
        CallerLookupDialog(number = number, onDismiss = { lookupNumber = null })
    }
}

@Composable
private fun CallRow(entry: CallLogEntity, onLongPress: () -> Unit) {
    val (icon, tint) = when (entry.type) {
        CallLog.Calls.MISSED_TYPE -> Icons.Filled.CallMissed to MaterialTheme.colorScheme.error
        CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade to MaterialTheme.colorScheme.primary
        else -> Icons.Filled.CallReceived to MaterialTheme.colorScheme.primary
    }
    val label = entry.name ?: PhoneUtils.pretty(entry.number)

    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(entry.id) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            com.kavach.caller.data.local.entity.ContactEntity(
                displayName = label,
                numbers = emptyList(),
            ),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    buildString {
                        append(timeLabel(entry.timestamp))
                        if (entry.durationSec > 0) append(" · ${PhoneUtils.formatDuration(entry.durationSec)}")
                        entry.phoneAccountId?.let { append(" · SIM") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (entry.spamScore >= 50) {
            Badge(containerColor = MaterialTheme.colorScheme.error) { Text("Spam") }
            Spacer(Modifier.width(6.dp))
        }
        if (entry.aiAnswered) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            ) {
                Text(
                    "AI",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale("en", "IN")).format(Date(timestamp))

private fun dayBucket(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)

    if (sameDay) return "Today"
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday"

    return SimpleDateFormat("dd MMM yyyy", Locale("en", "IN")).format(Date(timestamp))
}
