package com.kavach.caller.ui.screens.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.remote.KavachApiClient
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Was that call spam?"
 *
 * ## Why after the call and not during
 *
 * During a call the user is talking. After it, they know. The prompt is set when a
 * call ends and shown on the next app open, because a dialog that interrupts
 * someone the second they hang up is a dialog they dismiss without reading.
 *
 * ## What it refuses to do
 *
 * - **It never asks about a saved contact.** "Was that spam?" about your own
 *   family is how people learn to dismiss prompts unread.
 * - **It never asks about a missed call.** A call that did not connect tells you
 *   nothing about who was on the line.
 * - **It expires.** A call from three days ago is not a memory anyone has, and
 *   asking about it produces a guess filed as a report.
 * - **Reporting is a choice, not a default.** "Not spam" clears it and files
 *   nothing.
 */
@HiltViewModel
class SpamPromptViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: KavachApiClient,
) : ViewModel() {

    var pending by mutableStateOf<Pair<String, Long>?>(null)
        private set
    var result by mutableStateOf<String?>(null)
        private set

    /** A call older than this is not worth asking about. */
    private val expiryMillis = 24L * 60 * 60 * 1000

    fun load() = viewModelScope.launch {
        val raw = settings.settings.first().pendingSpamPrompt
        if (raw.isBlank()) return@launch
        val parts = raw.split('|')
        if (parts.size != 2) {
            settings.setPendingSpamPrompt("")
            return@launch
        }
        val number = parts[0]
        val whenMillis = parts[1].toLongOrNull() ?: 0L
        if (number.isBlank() || whenMillis <= 0L ||
            System.currentTimeMillis() - whenMillis > expiryMillis
        ) {
            // Expired or malformed: clear it rather than showing a stale prompt.
            settings.setPendingSpamPrompt("")
            return@launch
        }
        pending = number to whenMillis
    }

    fun report(category: String) = viewModelScope.launch {
        val number = pending?.first ?: return@launch
        settings.setPendingSpamPrompt("")
        pending = null
        val response = api.reportSpam(number, category)
        result = if (response == null) {
            // Said plainly: the report did not reach anyone, and pretending it did
            // would make the user think they had contributed.
            "Could not send that report. Check that a server is set under Settings."
        } else {
            "Reported. Thank you — it moves the score for that number."
        }
    }

    fun clearResult() { result = null }

    fun dismiss() = viewModelScope.launch {
        settings.setPendingSpamPrompt("")
        pending = null
    }
}

private val CATEGORIES = listOf(
    "telemarketing", "fraud", "loan", "insurance",
    "survey", "robocall", "silence", "other",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SpamPromptDialog(vm: SpamPromptViewModel = hiltViewModel()) {
    var chosen by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    vm.pending?.let { (number, _) ->
        AlertDialog(
            onDismissRequest = { vm.dismiss() },
            title = { Text("Was that call spam?") },
            text = {
                Column {
                    Text(number)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your report is counted as one person's experience, not as a " +
                            "finding. It is never used to publish a name.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CATEGORIES.forEach { category ->
                            AssistChip(
                                onClick = { chosen = category },
                                label = { Text(category.replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    if (chosen != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Reporting as: $chosen")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { chosen?.let { vm.report(it) } },
                    enabled = chosen != null,
                ) { Text("Report") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismiss() }) { Text("Not spam") }
            },
        )
    }

    vm.result?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismiss(); vm.clearResult() },
            title = { Text("Report") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.clearResult() }) { Text("OK") }
            },
        )
    }
}
