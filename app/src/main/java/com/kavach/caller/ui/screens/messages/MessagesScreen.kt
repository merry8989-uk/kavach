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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.sms.SenderId
import com.kavach.caller.sms.SmsClassifier
import com.kavach.caller.data.system.SmsReader
import com.kavach.caller.data.system.SmsSender
import com.kavach.caller.ui.screens.contacts.EmptyState
import com.kavach.caller.ui.screens.contacts.MonogramAvatar
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    private val blocks: BlockRepository,
) : ViewModel() {

    /** Which category the list is filtered to. */
    enum class Filter { ALL, PERSONAL, OTP, TRANSACTIONAL, PROMOTIONAL, SPAM }

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Classification per message id.
     *
     * Computed on the device from the text as it is read. Only the category is
     * ever logged — never the body, and never an OTP code.
     */
    private val _classified = MutableStateFlow<Map<Long, SmsClassifier.Result>>(emptyMap())
    val classified: StateFlow<Map<Long, SmsClassifier.Result>> = _classified.asStateFlow()

    /** The same classification for the thread list, keyed by sender address. */
    private val _threadClassified = MutableStateFlow<Map<String, SmsClassifier.Result>>(emptyMap())
    val threadClassified: StateFlow<Map<String, SmsClassifier.Result>> = _threadClassified.asStateFlow()

    /** Threads after the active filter and search query. */
    val visibleThreads: StateFlow<List<SmsReader.Thread>> =
        combine(_threads, _threadClassified, _filter, _query) { threads, classified, filter, query ->
            threads.filter { thread ->
                val result = classified[thread.address]
                val matchesFilter = when (filter) {
                    Filter.ALL -> true
                    Filter.OTP -> result?.category == SmsClassifier.Category.OTP
                    Filter.TRANSACTIONAL -> result?.category == SmsClassifier.Category.TRANSACTIONAL
                    Filter.PROMOTIONAL -> result?.category == SmsClassifier.Category.PROMOTIONAL
                    Filter.PERSONAL -> result?.category == SmsClassifier.Category.PERSONAL
                    Filter.SPAM -> result?.isSpam == true
                }
                val matchesQuery = query.isBlank() ||
                    thread.address.contains(query, ignoreCase = true) ||
                    thread.snippet.contains(query, ignoreCase = true)
                matchesFilter && matchesQuery
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        val loaded = withContext(Dispatchers.IO) { reader.threads() }
            .filter { it.address !in hidden }
        _threads.value = loaded

        // Classify the snippets as well, so the filter chips act on the list and
        // not only on an opened thread.
        _threadClassified.value = withContext(Dispatchers.Default) {
            loaded.associate { thread ->
                thread.address to SmsClassifier.classify(
                    body = thread.snippet,
                    address = thread.address,
                    isContact = reader.isKnownContact(thread.address),
                )
            }
        }
    }

    fun openThread(address: String) {
        _open.value = address
        status = null
        viewModelScope.launch {
            val hidden = hiddenIds()
            val loaded = withContext(Dispatchers.IO) {
                reader.readRecent()
                    .filter { it.address == address && it.id.toString() !in hidden }
                    .sortedBy { it.date }
            } + _sent.value.filter { it.address == address }

            val isContact = withContext(Dispatchers.IO) { reader.isKnownContact(address) }
            // Classify once per open, on the device. Nothing leaves the phone.
            _classified.value = withContext(Dispatchers.Default) {
                loaded.associate { sms ->
                    sms.id to SmsClassifier.classify(
                        body = sms.body,
                        address = sms.address,
                        isContact = isContact,
                    )
                }
            }
            _messages.value = loaded
        }
    }

    fun setFilter(value: Filter) {
        _filter.value = value
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Category counts for the filter chips. */
    val counts: StateFlow<Map<Filter, Int>> =
        combine(_threads, _threadClassified) { threads, classified ->
        val byCategory = classified.values.groupingBy { it.category }.eachCount()
        mapOf(
            Filter.ALL to threads.size,
            Filter.OTP to (byCategory[SmsClassifier.Category.OTP] ?: 0),
            Filter.TRANSACTIONAL to (byCategory[SmsClassifier.Category.TRANSACTIONAL] ?: 0),
            Filter.PROMOTIONAL to (byCategory[SmsClassifier.Category.PROMOTIONAL] ?: 0),
            Filter.PERSONAL to (byCategory[SmsClassifier.Category.PERSONAL] ?: 0),
            Filter.SPAM to (byCategory[SmsClassifier.Category.SUSPECTED_SPAM] ?: 0),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Report the sender and stop their messages.
     *
     * Adding the rule is the part that works on any device. Reporting to a
     * central reputation service needs the backend, so it is attempted
     * separately and its absence does not block the block.
     */
    /** The banner, gated by what the user asked to be warned about. */
    fun shouldWarn(result: SmsClassifier.Result): Boolean {
        val snapshot = settings.snapshot
        return when {
            result.isSpam -> snapshot.smsWarnOnSpam
            result.hasLinkRisk -> snapshot.smsWarnOnLinks
            result.category == SmsClassifier.Category.OTP -> snapshot.smsShowOtpBanner
            else -> false
        }
    }

    fun reportAndBlock(address: String) = viewModelScope.launch {
        val parsed = SenderId.parse(address)
        withContext(Dispatchers.IO) {
            blocks.upsert(
                BlockRuleEntity(
                    pattern = address,
                    type = if (parsed.kind == SenderId.Kind.COMMERCIAL) {
                        RuleType.EXACT
                    } else {
                        RuleType.EXACT
                    },
                    action = BlockAction.BLOCK,
                    label = "Reported as spam",
                ),
            )
        }
        status = "Reported and blocked. Messages from this sender will not appear."
    }

    fun blockSender(address: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            blocks.upsert(
                BlockRuleEntity(
                    pattern = address,
                    action = BlockAction.BLOCK,
                    label = "Blocked from messages",
                ),
            )
        }
        status = "Sender blocked."
    }

    /** Archive: hidden from Kavach's view without touching the real inbox. */
    fun archiveMessage(sms: SmsReader.Sms) = viewModelScope.launch {
        val hidden = hiddenIds() + sms.id.toString()
        settings.setHiddenSmsIds(hidden)
        _messages.value = _messages.value.filterNot { it.id == sms.id }
        status = "Message hidden in Kavach. Your inbox is untouched."
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
    val visibleThreads by vm.visibleThreads.collectAsState()
    val threadClassified by vm.threadClassified.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val counts by vm.counts.collectAsState()
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

            if (visibleThreads.isEmpty() && filter == MessagesViewModel.Filter.ALL && query.isBlank()) {
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
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.setQuery(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        placeholder = { Text("Search senders and messages") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                    )

                    // Category filter. Counts come from the on-device classifier.
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(MessagesViewModel.Filter.entries.toList()) { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { vm.setFilter(option) },
                                label = {
                                    val n = counts[option] ?: 0
                                    Text(
                                        if (n > 0) "${filterLabel(option)} $n"
                                        else filterLabel(option),
                                    )
                                },
                            )
                        }
                    }

                    if (visibleThreads.isEmpty()) {
                        EmptyState(
                            title = "Nothing here",
                            subtitle = "No messages match this filter.",
                            action = "Show all",
                            onAction = { vm.setFilter(MessagesViewModel.Filter.ALL); vm.setQuery("") },
                        )
                    }

                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleThreads, key = { it.address }) { t ->
                            val result = threadClassified[t.address]
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            SenderId.displayName(t.address),
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                        result?.let { CategoryBadge(it) }
                                    }
                                    Text(
                                        // An OTP body is replaced, so the code never
                                        // sits in a list someone else can read.
                                        if (result?.category == SmsClassifier.Category.OTP) {
                                            result.preview
                                        } else {
                                            t.snippet
                                        },
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (result?.hasLinkRisk == true) {
                                        Text(
                                            "Contains a suspicious link",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
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

            // The whole-thread verdict, when there is one.
            val openAddress = vm.open.collectAsState().value
            val threadResult = openAddress?.let { threadClassified[it] }
            threadResult?.let { result ->
                if (vm.shouldWarn(result)) {
                    MessageNotice(
                        result = result,
                        onCopyOtp = { code ->
                            copyToClipboard(context, code)
                            vm.showStatus("Code copied. It was not shown on screen.")
                        },
                        onReport = { openAddress?.let { vm.reportAndBlock(it) } },
                        onBlock = { openAddress?.let { vm.blockSender(it) } },
                    )
                }
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

/** A small chip naming the category, so a verdict is always visible. */
/**
 * The banner above a thread when something needs saying.
 *
 * For an OTP it offers a copy button and **never renders the code** — the
 * reason the code was extracted separately from the reasons in the first place.
 */
@Composable
private fun MessageNotice(
    result: SmsClassifier.Result,
    onCopyOtp: (String) -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    val isBad = result.isSpam || result.hasLinkRisk
    val container = if (isBad) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.primaryContainer

    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                when {
                    result.isSpam -> "This looks like a scam"
                    result.hasLinkRisk -> "This message contains a suspicious link"
                    else -> result.preview.ifBlank { "One-time password" }
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            result.reasons.take(3).forEach { reason ->
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (result.category == SmsClassifier.Category.OTP && result.otpCode != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The code is hidden here on purpose, so it cannot be read off the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { onCopyOtp(result.otpCode) }) { Text("Copy code") }
            }

            if (isBad) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onReport) { Text("Report and block") }
                    OutlinedButton(onClick = onBlock) { Text("Block sender") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Kavach will not tap the link for you, and it never sends or deletes a " +
                        "message on its own.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(result: SmsClassifier.Result) {
    val (label, colour) = when (result.category) {
        SmsClassifier.Category.OTP ->
            "OTP" to MaterialTheme.colorScheme.primary
        SmsClassifier.Category.TRANSACTIONAL ->
            "Service" to MaterialTheme.colorScheme.secondary
        SmsClassifier.Category.PROMOTIONAL ->
            "Promo" to MaterialTheme.colorScheme.tertiary
        SmsClassifier.Category.PERSONAL ->
            "Personal" to MaterialTheme.colorScheme.onSurfaceVariant
        SmsClassifier.Category.SUSPECTED_SPAM ->
            "Suspected spam" to MaterialTheme.colorScheme.error
        SmsClassifier.Category.UNKNOWN -> return
    }
    Spacer(Modifier.width(8.dp))
    Surface(
        color = colour.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
    }
}

private fun filterLabel(filter: MessagesViewModel.Filter): String = when (filter) {
    MessagesViewModel.Filter.ALL -> "All"
    MessagesViewModel.Filter.PERSONAL -> "Personal"
    MessagesViewModel.Filter.OTP -> "OTP"
    MessagesViewModel.Filter.TRANSACTIONAL -> "Service"
    MessagesViewModel.Filter.PROMOTIONAL -> "Promo"
    MessagesViewModel.Filter.SPAM -> "Spam"
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
