package com.kavach.caller.ui.screens.assistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.TranscriptEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.kavach.caller.R
import androidx.compose.ui.res.stringResource

@HiltViewModel
class AssistantViewModel @Inject constructor(
    repo: CallLogRepository,
) : ViewModel() {
    val briefs = repo.observeTranscripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** The AI call-assistant inbox (F10.7): one card per AI-answered call. */
@Composable
fun AssistantScreen(
    onBack: () -> Unit = {},
    vm: AssistantViewModel = hiltViewModel(),
) {
    val briefs by vm.briefs.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ai_call_briefs), style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.calls_kavach_answered_for_you_transcript_intent_sent),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (briefs.isEmpty()) {
            EmptyState(
                title = "No AI-answered calls yet",
                subtitle = "When an unknown caller rings and the assistant answers, the brief lands here.",
                action = "Got it",
                onAction = {},
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(briefs, key = { it.id }) { BriefCard(it) }
            }
        }
    }
}

@Composable
private fun BriefCard(t: TranscriptEntity) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(PhoneUtils.pretty(t.number), fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Badge(containerColor = riskColor(t.risk)) { Text(stringResource(R.string.risk_label, t.risk)) }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                AssistChip(onClick = {}, label = { Text(t.intent) })
                Spacer(Modifier.width(6.dp))
                AssistChip(onClick = {}, label = { Text(t.sentiment) })
            }
            Spacer(Modifier.height(10.dp))
            Text(t.summary ?: t.transcript.take(160),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!t.summary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp),
                ) {
                    Text("Transcript: ${t.transcript}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun riskColor(risk: Int) = when {
    risk >= 70 -> MaterialTheme.colorScheme.error
    risk >= 40 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}
