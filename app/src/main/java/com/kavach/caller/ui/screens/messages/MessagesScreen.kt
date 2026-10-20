package com.kavach.caller.ui.screens.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.system.SmsReader
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.ui.screens.contacts.MonogramAvatar
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val reader: SmsReader,
) : ViewModel() {

    private val _threads = MutableStateFlow<List<SmsReader.Thread>>(emptyList())
    val threads: StateFlow<List<SmsReader.Thread>> = _threads.asStateFlow()

    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> = _open.asStateFlow()

    private val _messages = MutableStateFlow<List<SmsReader.Sms>>(emptyList())
    val messages: StateFlow<List<SmsReader.Sms>> = _messages.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _threads.value = withContext(Dispatchers.IO) { reader.threads() }
    }

    fun openThread(address: String) {
        _open.value = address
        viewModelScope.launch {
            _messages.value = withContext(Dispatchers.IO) {
                reader.readRecent()
                    .filter { it.address == address }
                    .sortedBy { it.date }
            }
        }
    }

    fun closeThread() {
        _open.value = null
    }

    /** Send an SMS, then refresh. No-ops (silently) without SEND_SMS. */
    fun send(context: Context, address: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault().sendTextMessage(address, null, text, null, null)
                    }
                }
            }
            openThread(address)
            refresh()
        }
    }
}

@Composable
fun MessagesScreen(vm: MessagesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val threads by vm.threads.collectAsState()
    val open by vm.open.collectAsState()
    val messages by vm.messages.collectAsState()

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
            if (threads.isEmpty()) {
                EmptyState(
                    title = "No messages",
                    subtitle = "Grant SMS access to see and reply to your threads.",
                    action = "Refresh",
                    onAction = { vm.refresh() },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(threads, key = { it.address }) { t ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.openThread(t.address) }
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
                        ) {
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
            var draft by remember(current) { mutableStateOf("") }
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
}
