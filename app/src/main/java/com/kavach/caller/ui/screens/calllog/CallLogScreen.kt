package com.kavach.caller.ui.screens.calllog

import android.provider.CallLog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.ui.screens.contacts.MonogramAvatar
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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

    fun sync() = viewModelScope.launch { repo.syncFromSystem() }

    enum class Filter(val label: String) {
        ALL("All"), MISSED("Missed"), SPAM("Spam"), AI("AI answered"), RECORDED("Recorded")
    }

    fun apply(all: List<CallLogEntity>): List<CallLogEntity> = when (filter) {
        Filter.ALL -> all
        Filter.MISSED -> all.filter { it.type == CallLog.Calls.MISSED_TYPE }
        Filter.SPAM -> all.filter { it.spamScore >= 50 }
        Filter.AI -> all.filter { it.aiAnswered }
        Filter.RECORDED -> all.filter { it.recordingId != null }
    }
}

@Composable
fun CallLogScreen(
    onOpenAssistant: () -> Unit = {},
    vm: CallLogViewModel = hiltViewModel(),
) {
    val all by vm.entries.collectAsState()
    val list = vm.apply(all)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Call log",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onOpenAssistant) { Text("AI briefs") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
        ) {
            CallLogViewModel.Filter.entries.forEach { f ->
                FilterChip(
                    selected = vm.filter == f,
                    onClick = { vm.filter = f },
                    label = { Text(f.label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        if (all.isEmpty()) {
            EmptyState(
                title = "No call history",
                subtitle = "Mirror your device call log to enrich it with spam scores and AI briefs.",
                action = "Import call log",
                onAction = { vm.sync() },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { e ->
                    CallRow(e)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun CallRow(e: CallLogEntity) {
    val icon = when (e.type) {
        CallLog.Calls.MISSED_TYPE -> Icons.Filled.CallMissed
        CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade
        else -> Icons.Filled.CallReceived
    }
    val label = e.name ?: PhoneUtils.pretty(e.number)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(label)
        Spacer(Modifier.width(14.dp))
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                timeAgo(e.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (e.spamScore >= 50) {
            Badge { Text("Spam ${e.spamScore}") }
        }
        if (e.aiAnswered) {
            Spacer(Modifier.width(6.dp))
            AssistChip(onClick = {}, label = { Text("AI") })
        }
    }
}

private fun timeAgo(ts: Long): String {
    val fmt = SimpleDateFormat("dd MMM, HH:mm", Locale("en", "IN"))
    return fmt.format(Date(ts))
}
