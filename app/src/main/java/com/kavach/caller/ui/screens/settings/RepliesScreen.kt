package com.kavach.caller.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.repo.ReplySettingsRepository
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RepliesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val replies: ReplySettingsRepository,
) : ViewModel() {

    val appSettings = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val suggested = replies.replies
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ReplySettingsRepository.DEFAULT_REPLIES,
        )

    var message by mutableStateOf<String?>(null)
        private set

    fun dismiss() {
        message = null
    }

    fun add(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        replies.addReply(text)
        message = "Added."
    }

    fun remove(text: String) = viewModelScope.launch { replies.removeReply(text) }

    fun move(index: Int, delta: Int) = viewModelScope.launch {
        replies.move(index, index + delta)
    }

    fun restoreDefaults() = viewModelScope.launch {
        replies.save(ReplySettingsRepository.DEFAULT_REPLIES)
        message = "Defaults restored."
    }

    fun setAutoReplyEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setAutoReplyEnabled(enabled)
    }

    fun setAutoReplyText(text: String) = viewModelScope.launch {
        settings.setAutoReplyText(text)
    }
}

/**
 * Suggested replies and auto-reply.
 *
 * Both are plain user-controlled settings: the reply row is a shortcut while
 * typing, and auto-reply is off until it is turned on and says exactly what it
 * will send.
 */
@Composable
fun RepliesScreen(
    onBack: () -> Unit,
    vm: RepliesViewModel = hiltViewModel(),
) {
    val s by vm.appSettings.collectAsState()
    val suggested by vm.suggested.collectAsState()
    var newReply by remember { mutableStateOf("") }
    var autoReplyDraft by remember(s.autoReplyText) { mutableStateOf(s.autoReplyText) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Replies",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            vm.message?.let { text ->
                KavachCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { vm.dismiss() }) { Text("OK") }
                    }
                }
            }

            SectionHeader("Suggested replies")
            KavachCard {
                Text(
                    "These appear as one-tap chips while you are typing a message. " +
                        "Drag-free ordering: use the arrows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                suggested.forEachIndexed { index, reply ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            reply,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = { vm.move(index, -1) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(
                            onClick = { vm.move(index, 1) },
                            enabled = index < suggested.lastIndex,
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                        }
                        IconButton(onClick = { vm.remove(reply) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newReply,
                    onValueChange = { newReply = it },
                    label = { Text("Add a reply") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.add(newReply); newReply = "" },
                        enabled = newReply.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add")
                    }
                    OutlinedButton(
                        onClick = { vm.restoreDefaults() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Reset") }
                }
            }

            SectionHeader("Auto-reply")
            KavachCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reply automatically", color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Sends the message below to anyone who texts you. Off by default — " +
                                "it sends without asking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.autoReplyEnabled,
                        onCheckedChange = { vm.setAutoReplyEnabled(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = autoReplyDraft,
                    onValueChange = { autoReplyDraft = it },
                    label = { Text("Auto-reply message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.setAutoReplyText(autoReplyDraft) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = autoReplyDraft.isNotBlank(),
                ) { Text("Save auto-reply") }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Honest limits: Kavach replies once per incoming message, never to a " +
                        "shortcode, and never to its own reply. Without being the default " +
                        "messaging app it cannot mark the conversation as read, and the reply " +
                        "appears in your normal messaging thread.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (suggested.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap to use a suggestion:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    suggested.forEach { reply ->
                        Text(
                            reply,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { autoReplyDraft = reply }
                                .padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
