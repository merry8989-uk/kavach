package com.kavach.caller.ui.screens.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.remote.KavachApiClient
import com.kavach.caller.data.remote.ScoreResultDto
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.ui.components.KavachCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Community reports on the numbers that actually called you.
 *
 * **Why this shape.** The server stores only hashes, so it cannot produce a
 * leaderboard of the world's spammiest numbers — it does not know any number. What
 * it can do is answer about numbers the app already has. So this screen takes the
 * recent call log and asks about those, which is also the only list that matters
 * to the person reading it.
 *
 * A number nobody has reported is shown as **"no reports"**, never as clean.
 * Those are different claims and the difference is the whole point of the
 * feature.
 */
@HiltViewModel
class CommunitySpamViewModel @Inject constructor(
    private val api: KavachApiClient,
    private val callLog: CallLogRepository,
) : ViewModel() {

    var results by mutableStateOf<List<ScoreResultDto>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var hasRun by mutableStateOf(false)
        private set

    fun refresh() = viewModelScope.launch {
        busy = true
        message = null
        runCatching {
            val recent = callLog.observeAll().first()
                .mapNotNull { it.number }
                .filter { it.isNotBlank() }
                .distinct()
                .take(100)

            if (recent.isEmpty()) {
                message = "No calls in the log yet, so there is nothing to check."
                return@launch
            }

            val scores = api.batchScores(recent)
            if (scores == null) {
                // Said plainly. An empty list would read as "no spam found", which
                // is a different and false statement.
                message = "No server is set. Add its address under Settings, " +
                    "Self-hosted lookup, to check numbers against community reports."
                return@launch
            }
            results = scores
                .filter { it.reports > 0 }
                .sortedWith(compareByDescending<ScoreResultDto> { it.reports }.thenByDescending { it.spamRisk })
            hasRun = true
            if (results.isEmpty()) {
                message = "None of your recent callers have been reported by anyone yet."
            }
        }.onFailure {
            message = "Could not reach the server: ${it.javaClass.simpleName}."
        }
        busy = false
    }
}

@Composable
fun CommunitySpamScreen(
    onBack: () -> Unit,
    vm: CommunitySpamViewModel = hiltViewModel(),
) {
    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Community reports",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))

            Text(
                "Numbers that called you, and what other people have reported about " +
                    "them. Each report is one person's experience, not a finding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Button(onClick = { vm.refresh() }, enabled = !vm.busy) {
                Text(if (vm.busy) "Checking…" else "Check my recent callers")
            }

            vm.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.results) { result ->
                    KavachCard {
                        Text(
                            result.number,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append("${result.reports} ")
                                append(if (result.reports == 1) "report" else "reports")
                                append(" · risk ${result.spamRisk}/95")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
