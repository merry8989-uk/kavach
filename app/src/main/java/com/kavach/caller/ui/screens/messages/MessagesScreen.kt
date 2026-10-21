package com.kavach.caller.ui.screens.messages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.repo.ReplySettingsRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.data.system.SmsReader
import com.kavach.caller.data.system.SmsSender
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.ui.screens.contacts.MonogramAvatar
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val reader: SmsReader,
    private val sender: SmsSender,
    private val settings: SettingsRepository,
    private val replies: ReplySettingsRepository,
) : ViewModel() {

    private val _threads = MutableStateFlow<List<SmsReader.Thread>>(emptyList())
    val threads: StateFlow<List<SmsReader.Thread>> = _threads.asStateFlow()

    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open.asStateFlow()

    private val _messages = MutableStateFlow<List<SmsReader.Sms>>(emptyList())
    val messages: StateFlow<List<SmsReader.Sms>> = _messages.asStateFlow()

    /** Messages sent from Kavach that the provider may not show back to us. */
    private val _sent = MutableStateFlow<List<SmsReader.Sms>>(emptyList())

    var status by mutableStateOf<String?>(null)
        private set

    val suggested: StateFlow<List<String>> = replies.replies.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReplySettingsRepository.DEFAULT_REPLIES,
    )

    val canSend: Boolean get() = sender.canSend()

    fun dismissStatus() {
        status = null
    }

    fun showStatus(message: String) {
        status = message
    }

    private fun hiddenIds(): Set<String> = settings.snapshot.hiddenSmsIds

    fun refresh() = viewModelScope.launch {
        val hidden = hiddenIds()
        _threads.value = withContext(Dispatchers.IO) { reader.threads() }
            .filter { it.address !in hidden }
    }

    fun openThread(address: String) {
        _open.value = address
        status = null
        viewModelScope.launch {
            val hidden = hiddenIds()
            _messages.value = withContext(Dispatchers.IO) {
                reader.readRecent()
                    .filter { it.address == address && it.id.toString() !in hidden }
                    .sortedBy { it.date }
            } + _sent.value.filter { it.address == address }
        }
    }

    fun closeThread() {
        _open.value = null
    }

    /**
     * Send an SMS and report what happened.
     *
     * The message is echoed locally straight away, because without READ_SMS (or
     * without being the default SMS app) the provider may never show it back to
     * us — and a message that vanishes after sending looks like a failure.
     */
    fun send(context: Context, address: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { sender.send(address, text) }) {
                is SmsSender.Result.Sent -> {
                    _sent.value = _sent.value + SmsReader.Sms(
                        id = -System.currentTimeMillis(),
                        address = address,
                        body = text,
                        date = System.currentTimeMillis(),
                        type = Telephony.Sms.MESSAGE_TYPE_SENT,
                        read = true,
                    )
                    status = "Message sent."
                    openThread(address)
                    refresh()
                }

                is SmsSender.Result.Failed -> status = result.reason
            }
        }
    }

    /**
     * Delete a message.
     *
     * Android only lets the **default SMS app** delete from the SMS provider, so
     * a normal app's delete is refused. Rather than pretending, we try, and fall
     * back to removing it from Kavach's own view — and say which happened.
     */
    fun deleteMessage(context: Context, sms: SmsReader.Sms) = viewModelScope.launch {
        val removed = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.delete(
                    Uri.parse("content://sms/${sms.id}"),
                    null,
                    null,
                ) > 0
            }.getOrDefault(false)
        }
        if (removed) {
            status = "Message deleted."
        } else {
            settings.hideSmsIds(setOf(sms.id.toString()))
            _sent.value = _sent.value.filterNot { it.id == sms.id }
            status = "Removed from Kavach. Android only lets the default messaging " +
                "app delete messages from the phone itself."
        }
        open.value?.let { openThread(it) }
        refresh()
    }

    fun deleteThread(context: Context, address: String) = viewModelScope.launch {
        val ids = withContext(Dispatchers.IO) {
            reader.readRecent().filter { it.address == address }.map { it.id.toString() }
        }
        val removed = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.delete(
                    Uri.parse("content://sms"),
                    "address = ?",
                    arrayOf(address),
                ) > 0
            }.getOrDefault(false)
        }
        if (!removed) {
            settings.hideSmsIds(ids.toSet() + address)
            status = "Thread hidden in Kavach. Deleting it from the phone needs the " +
                "default messaging app."
        } else {
            status = "Thread deleted."
        }
        _open.value = null
        refresh()
    }

    fun restoreHidden() = viewModelScope.launch {
        settings.clearHiddenSms()
        status = "Hidden messages restored."
        refresh()
    }
}

@Composable
fun MessagesScreen(vm: MessagesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val threads by vm.threads.collectAsState()
    val open by vm.open.collectAsState()
    val messages by vm.messages.collectAsState()
    val suggested by vm.suggested.collectAsState()

    var actionTarget by remember { mutableStateOf<SmsReader.Sms?>(null) }
    var confirmThreadDelete by remember { mutableStateOf<String?>(null) }
    var draft by remember(open) { mutableStateOf("") }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refresh() }

    LaunchedEffect(Unit) { vm.refresh() }

    val current = open
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Messages",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )

            vm.status?.let { text ->
                StatusBanner(text) { vm.dismissStatus() }
            }

            if (threads.isEmpty()) {
                EmptyState(
                    title = "No messages",
                    subtitle = "Grant SMS access to see and reply to your threads.",
                    action = "Grant SMS access",
                    onAction = {
                        smsPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.RECEIVE_SMS,
                            ),
                        )
                    },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(threads, key = { it.address }) { t ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.openThread(t.address) }
                                .pointerInput(t.address) {
                                    detectTapGestures(
                                        onLongPress = { confirmThreadDelete = t.address },
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MonogramAvatar(t.address)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.address, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground)
                                Text(t.snippet, maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (t.unreadCount > 0) Badge { Text("${t.unreadCount}") }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.closeThread() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(PhoneUtils.pretty(current), fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
            }

            vm.status?.let { text ->
                StatusBanner(text) { vm.dismissStatus() }
            }

            if (!vm.canSend) {
                Text(
                    "Sending is off — SMS permission is not granted.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                items(messages, key = { it.id }) { m ->
                    val incoming = m.type == Telephony.Sms.MESSAGE_TYPE_INBOX
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
                    ) {
                        Surface(
                            color = if (incoming) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.pointerInput(m.id) {
                                detectTapGestures(onLongPress = { actionTarget = m })
                            },
                        ) {
                            // SelectionContainer is what makes partial copy work:
                            // the system selection handles appear on long-press.
                            SelectionContainer {
                                Text(
                                    m.body,
                                    modifier = Modifier.padding(10.dp),
                                    color = if (incoming) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }

            // ---- suggested replies ----
            if (suggested.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(suggested) { reply ->
                        AssistChip(
                            onClick = {
                                draft = if (draft.isBlank()) reply else "$draft $reply"
                            },
                            label = { Text(reply, maxLines = 1) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                IconButton(onClick = { vm.send(context, current, draft); draft = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    // ---- per-message actions ----
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("Message") },
            text = {
                Column {
                    Text(
                        target.body,
                        maxLines = 4,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MessageAction("Copy whole message") {
                        copyToClipboard(context, target.body)
                        vm.showStatus("Message copied.")
                        actionTarget = null
                    }
                    MessageAction("Select part of the text") {
                        vm.showStatus("Long-press the message text, then drag to select part of it.")
                        actionTarget = null
                    }
                    MessageAction("Forward") {
                        forward(context, target.body)
                        actionTarget = null
                    }
                    MessageAction("Delete") {
                        vm.deleteMessage(context, target)
                        actionTarget = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) { Text("Close") }
            },
        )
    }

    confirmThreadDelete?.let { address ->
        AlertDialog(
            onDismissRequest = { confirmThreadDelete = null },
            title = { Text("Delete this thread?") },
            text = {
                Text(
                    "Android only lets the default messaging app delete messages from the " +
                        "phone. If Kavach cannot, the thread will be hidden inside Kavach " +
                        "instead — nothing is lost from your phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmThreadDelete = null; vm.deleteThread(context, address) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmThreadDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun MessageAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("message", text))
}

private fun forward(context: Context, body: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
