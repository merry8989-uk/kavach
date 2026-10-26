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
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import com.kavach.caller.ui.permissions.PermissionNotice
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.derivedStateOf
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import com.kavach.caller.data.system.SmsDeliveryTracker
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.text.selection.SelectionContainer

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val reader: SmsReader,
    private val sender: SmsSender,
    private val settings: SettingsRepository,
    private val replies: ReplySettingsRepository,
    private val blocks: BlockRepository,
    private val delivery: SmsDeliveryTracker,
) : ViewModel() {

    /**
     * What happened to an outgoing message, in a word.
     *
     * Matched on address and body because the tracker is keyed by the reference
     * the platform hands back, and a locally-stored sent message does not carry
     * one. That is a real limitation and it is why the label is a best effort
     * rather than a guarantee — an identical message sent twice would show the
     * most recent attempt's state, which is the one the user cares about.
     */
    fun deliveryLabelFor(address: String, body: String): String? {
        val record = delivery.recent.value.firstOrNull {
            it.address == address && it.text == body
        } ?: return null
        return when (record.state) {
            SmsDeliveryTracker.State.QUEUED -> "Sending…"
            SmsDeliveryTracker.State.SENT -> "Sent"
            SmsDeliveryTracker.State.DELIVERED -> "Delivered"
            SmsDeliveryTracker.State.FAILED -> record.reason ?: "Not sent"
        }
    }

    /** Which category the list is filtered to. */
    enum class Filter { ALL, PERSONAL, OTP, TRANSACTIONAL, PROMOTIONAL, SPAM }

    /**
     * Which box the list is showing.
     *
     * Archiving is a place, not a deletion, and the bin is a place the user can
     * see into and empty. Both are stored as the set of addresses moved there, so
     * they survive a restart without a second copy of any message.
     */
    enum class Mailbox { INBOX, ARCHIVED, BIN }

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _mailbox = MutableStateFlow(Mailbox.INBOX)
    val mailbox: StateFlow<Mailbox> = _mailbox.asStateFlow()

    /**
     * Where each conversation lives, and how it behaves.
     *
     * One object rather than a growing tuple: adding pin and mute to a `Triple`
     * would have meant a `Quintuple` and a positional destructure nobody can read.
     */
    data class Boxes(
        val archived: Set<String> = emptySet(),
        val binned: Set<String> = emptySet(),
        val pinned: Set<String> = emptySet(),
        val muted: Set<String> = emptySet(),
        val silent: Set<String> = emptySet(),
        val markedAllReadAt: Long = 0L,
    )

    private val _boxes = MutableStateFlow(Boxes())
    val boxes = _boxes.asStateFlow()

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

    private val _threads = MutableStateFlow<List<SmsReader.Thread>>(emptyList())
    val threads: StateFlow<List<SmsReader.Thread>> = _threads.asStateFlow()

    /** Threads after the active filter and search query. */
    val visibleThreads: StateFlow<List<SmsReader.Thread>> =
        combine(
            _threads, _threadClassified, _filter, _query, _mailbox, _boxes,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val threads = values[0] as List<SmsReader.Thread>
            val classified = values[1] as Map<String, SmsClassifier.Result>
            val filter = values[2] as Filter
            val query = values[3] as String
            val mailbox = values[4] as Mailbox
            val boxes = values[5] as Boxes

            threads.filter { thread ->
                val inThisBox = when (mailbox) {
                    Mailbox.INBOX -> thread.address !in boxes.archived && thread.address !in boxes.binned
                    Mailbox.ARCHIVED -> thread.address in boxes.archived
                    Mailbox.BIN -> thread.address in boxes.binned
                }
                if (!inThisBox) return@filter false
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
                // Pinned first, but only in the inbox: a pin is about what you
                // want at the top of your messages, not about the archive.
                .let { list ->
                    if (mailbox == Mailbox.INBOX) {
                        list.sortedWith(
                            compareByDescending<SmsReader.Thread> { it.address in boxes.pinned }
                                .thenByDescending { it.date },
                        )
                    } else {
                        list
                    }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        // A selection or a reply belongs to the conversation it was made in.
        clearSelection()
        clearReply()
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

    // ---- selection ----
    //
    // Long-press starts it, tap adds to it, and the contextual bar acts on the
    // set. This is the pattern every messaging app uses, and its absence was the
    // complaint: there was no way to pick a message at all.

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected = _selected.asStateFlow()

    fun isSelecting(): Boolean = _selected.value.isNotEmpty()

    fun toggleSelected(id: Long) {
        val current = _selected.value
        _selected.value = if (id in current) current - id else current + id
    }

    fun clearSelection() { _selected.value = emptySet() }

    // Text-selection mode is UI state, so it is cleared by the screen when the
    // thread changes rather than stored.

    /** The selected messages, in the order they appear. */
    fun selectedMessages(): List<SmsReader.Sms> {
        val ids = _selected.value
        return _messages.value.filter { it.id in ids }
    }

    // ---- reply ----

    private val _replyTo = MutableStateFlow<SmsReader.Sms?>(null)
    val replyTo = _replyTo.asStateFlow()

    fun setReplyTo(message: SmsReader.Sms?) { _replyTo.value = message }

    fun clearReply() { _replyTo.value = null }

    /** What a quoted reply looks like in the sent text. */
    fun quotePrefix(): String {
        val quoted = _replyTo.value ?: return ""
        val who = if (quoted.type == Telephony.Sms.MESSAGE_TYPE_INBOX) "them" else "you"
        val line = quoted.body.take(60).replace("\n", " ")
        return "> $who: $line\n\n"
    }

    fun setMailbox(value: Mailbox) {
        _mailbox.value = value
        // The category chips are about the inbox; carrying one into the bin would
        // hide everything and read as the bin being empty.
        if (value != Mailbox.INBOX) _filter.value = Filter.ALL
    }

    init {
        viewModelScope.launch {
            settings.settings.collect { snapshot ->
                _boxes.value = Boxes(
                    archived = snapshot.archivedSmsThreads,
                    binned = snapshot.binnedSmsThreads,
                    pinned = snapshot.pinnedSmsThreads,
                    muted = snapshot.mutedSmsThreads,
                    silent = snapshot.silentSmsThreads,
                    markedAllReadAt = snapshot.smsMarkedAllReadAt,
                )
            }
        }
    }

    fun archive(address: String) = viewModelScope.launch {
        settings.setThreadArchived(address, true)
        settings.setThreadBinned(address, false)
    }

    fun unarchive(address: String) = viewModelScope.launch { settings.setThreadArchived(address, false) }

    fun bin(address: String) = viewModelScope.launch {
        settings.setThreadBinned(address, true)
        settings.setThreadArchived(address, false)
    }

    fun restore(address: String) = viewModelScope.launch { settings.setThreadBinned(address, false) }

    fun emptyBin() = viewModelScope.launch { settings.emptyBin() }

    fun pin(address: String) = viewModelScope.launch {
        settings.setThreadPinned(address, address !in settings.snapshot.pinnedSmsThreads)
    }

    fun mute(address: String) = viewModelScope.launch {
        settings.setThreadMuted(address, address !in settings.snapshot.mutedSmsThreads)
    }

    // ---- thread search, star, and the part counter ----

    private val _threadQuery = MutableStateFlow("")
    val threadQuery = _threadQuery.asStateFlow()

    fun setThreadQuery(value: String) { _threadQuery.value = value }

    fun star(id: Long) = viewModelScope.launch {
        val current = settings.snapshot.starredSmsIds
        settings.setMessageStarred(id, id.toString() !in current)
    }

    fun isStarred(id: Long): Boolean = id.toString() in settings.snapshot.starredSmsIds

    fun starredIds(): Set<String> = settings.snapshot.starredSmsIds

    /**
     * How many SMS parts [text] becomes, and why.
     *
     * The limit is not one number. A message that is pure GSM-7 fits 160
     * characters in one part and 153 in each part after that, because the
     * concatenation header eats seven. A single character outside GSM-7 — an
     * emoji, a Devanagari letter, a curly quote — switches the whole message to
     * UCS-2, where the numbers are 70 and 67. Telling someone "160 left" and then
     * charging them for two messages is the kind of thing this exists to avoid.
     */
    fun partInfo(text: String): PartInfo {
        if (text.isEmpty()) return PartInfo(0, 0, 1, false)
        val unicode = text.any { !isGsm7(it) }
        val single = if (unicode) 70 else 160
        val multi = if (unicode) 67 else 153
        val length = text.length
        val parts = if (length <= single) 1 else (length + multi - 1) / multi
        val used = if (parts == 1) length else length - (parts - 1) * multi
        val left = if (parts == 1) single - length else multi - used
        return PartInfo(length, left, parts, unicode)
    }

    data class PartInfo(
        val length: Int,
        val left: Int,
        val parts: Int,
        /** True when the message is UCS-2, which is why the limits are lower. */
        val unicode: Boolean,
    )

    private fun isGsm7(ch: Char): Boolean {
        // The GSM 03.38 basic set, plus the extension characters that cost two
        // septets. Anything outside switches the message to UCS-2.
        val basic = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        if (ch in basic) return true
        return ch in "^{}\\[~]|€"
    }

    fun silence(address: String) = viewModelScope.launch {
        settings.setThreadSilent(address, address !in settings.snapshot.silentSmsThreads)
    }

    fun isPinned(address: String): Boolean = address in settings.snapshot.pinnedSmsThreads
    fun isMuted(address: String): Boolean = address in settings.snapshot.mutedSmsThreads

    // ---- list multi-select ----

    private val _pickedThreads = MutableStateFlow<Set<String>>(emptySet())
    val pickedThreads = _pickedThreads.asStateFlow()

    fun togglePicked(address: String) {
        val current = _pickedThreads.value
        _pickedThreads.value = if (address in current) current - address else current + address
    }

    fun clearPicked() { _pickedThreads.value = emptySet() }

    /** Archive or bin several at once, which is the point of picking them. */
    fun actOnPicked(action: String) = viewModelScope.launch {
        val picked = _pickedThreads.value
        picked.forEach { address ->
            when (action) {
                "archive" -> { settings.setThreadArchived(address, true); settings.setThreadBinned(address, false) }
                "bin" -> { settings.setThreadBinned(address, true); settings.setThreadArchived(address, false) }
                "read" -> Unit
            }
        }
        if (action == "read") settings.setMarkedAllReadAt(System.currentTimeMillis())
        _pickedThreads.value = emptySet()
    }

    /**
     * Allow or stop this conversation bubbling.
     *
     * Only offered when the bubble setting is "selected" — with "all" it is
     * already true and with "none" it cannot be turned on, so a control there
     * would be one that does nothing.
     */
    fun toggleBubbled(address: String) = viewModelScope.launch {
        val now = settings.snapshot.bubbledSmsThreads
        settings.setThreadBubbled(address, address !in now)
    }

    fun isBubbled(address: String): Boolean = address in settings.snapshot.bubbledSmsThreads

    /**
     * Mark everything read.
     *
     * Stored as a timestamp rather than by clearing a flag on each message,
     * because reading is the system's business, not ours — Kavach is not
     * necessarily the default SMS app and has no right to rewrite the provider.
     * The timestamp is what the badge honours.
     */
    fun markAllRead() = viewModelScope.launch {
        settings.setMarkedAllReadAt(System.currentTimeMillis())
    }

    /** Whether the list shows Today / Yesterday / Older seams. */
    fun groupByTime(): Boolean = settings.snapshot.smsGroupByTime

    /** What a swipe does right now — "none" disables the gesture entirely. */
    fun currentSwipeAction(): String = settings.snapshot.smsSwipeAction

    /** Apply the swipe action the user chose. */
    fun swipe(address: String) = viewModelScope.launch {
        when (settings.snapshot.smsSwipeAction) {
            "archive" -> archive(address)
            "delete" -> bin(address)
            else -> Unit
        }
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
        // A reply carries what it is replying to, because the quote is only
        // useful to the person receiving it — keeping it on screen and not in the
        // message would be decoration.
        val outgoing = quotePrefix() + text
        viewModelScope.launch {
            val prefs = settings.snapshot
            when (
                val result = withContext(Dispatchers.IO) {
                    sender.send(
                        address = address,
                        text = outgoing,
                        deliveryReports = prefs.smsDeliveryReports,
                        autoRetry = prefs.smsAutoRetry,
                    )
                }
            ) {
                // Queued rather than Sent: the radio has the message, and whether
                // it accepted it arrives separately. Saying "sent" here would be
                // claiming something the app does not know yet.
                is SmsSender.Result.Queued -> {
                    if (prefs.smsSoundsEnabled) playSendSound(context)
                    _sent.value = _sent.value + SmsReader.Sms(
                        id = -System.currentTimeMillis(),
                        address = address,
                        body = outgoing,
                        date = System.currentTimeMillis(),
                        type = Telephony.Sms.MESSAGE_TYPE_SENT,
                        read = true,
                    )
                    status = if (prefs.smsDeliveryReports) {
                        "Sending. You will be told when the network accepts it."
                    } else {
                        "Sending…"
                    }
                    clearReply()
                    openThread(address)
                    refresh()
                }

                is SmsSender.Result.Sent -> {
                    _sent.value = _sent.value + SmsReader.Sms(
                        id = -System.currentTimeMillis(),
                        address = address,
                        body = outgoing,
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

/**
 * Messaging, in the theme the user chose for it.
 *
 * The body is a separate composable so the theme wraps all of it — including the
 * conversation view, which is a different branch and would otherwise be the one
 * screen in the app that ignored the setting.
 */
@Composable
fun MessagesScreen(
    onOpenSettings: () -> Unit = {},
    onOpenBlocklist: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    vm: MessagesViewModel = hiltViewModel(),
) {
    MessagesTheme {
        MessagesScreenContent(
            onOpenSettings = onOpenSettings,
            onOpenBlocklist = onOpenBlocklist,
            onOpenHelp = onOpenHelp,
            vm = vm,
        )
    }
}

// combinedClickable is still experimental in foundation, which is why the
// opt-in is here as well as on the wrapper.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessagesScreenContent(
    onOpenSettings: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenHelp: () -> Unit,
    vm: MessagesViewModel,
) {
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
    /**
     * The conversation whose options are open.
     *
     * Long-pressing used to jump straight to "delete this thread", which offered
     * one action and it was the destructive one. This is a menu.
     */
    var threadMenuFor by remember { mutableStateOf<String?>(null) }
    var draft by remember(open) { mutableStateOf("") }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.refresh() }

    LaunchedEffect(Unit) { vm.refresh() }

    var showMenu by remember { mutableStateOf(false) }
    val mailbox by vm.mailbox.collectAsState()
    // Read once for the gesture, so the swipe knows whether it does anything.
    val swipeAction by remember { derivedStateOf { vm.currentSwipeAction() } }
    val groupByTime by remember { derivedStateOf { vm.groupByTime() } }
    var showThreadSearch by remember { mutableStateOf(false) }
    /**
     * The one message whose text is selectable right now.
     *
     * Null means no bubble is in text-selection mode, which is the normal state —
     * long-press then belongs to multi-select.
     */
    var textSelectFor by remember { mutableStateOf<Long?>(null) }
    val threadQuery by vm.threadQuery.collectAsState()
    val boxes by vm.boxes.collectAsState()
    val pinned = boxes.pinned
    val muted = boxes.muted
    val picked by vm.pickedThreads.collectAsState()
    val selectedIds by vm.selected.collectAsState()
    val replyTo by vm.replyTo.collectAsState()

    fun deliveryStateFor(m: SmsReader.Sms): String? =
        vm.deliveryLabelFor(vm.open.value.orEmpty(), m.body)

    val current = open
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
            PermissionNotice(
                permissions = listOf(android.Manifest.permission.READ_SMS),
                title = "Kavach cannot read your messages yet",
                rationale = "Reading SMS is what powers the thread list, spam filtering and " +
                    "one-time-password detection.",
            )
            // ---- header, with the overflow menu ----
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (mailbox) {
                            MessagesViewModel.Mailbox.INBOX -> stringResource(R.string.messages)
                            MessagesViewModel.Mailbox.ARCHIVED -> "Archived"
                            MessagesViewModel.Mailbox.BIN -> "Bin"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (mailbox == MessagesViewModel.Mailbox.ARCHIVED) {
                        Text(
                            "Conversations you archived. They still arrive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mailbox == MessagesViewModel.Mailbox.BIN) {
                        Text(
                            "Hidden from Kavach. Your carrier still has them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (mailbox != MessagesViewModel.Mailbox.INBOX) {
                            DropdownMenuItem(
                                text = { Text("Back to inbox") },
                                onClick = { vm.setMailbox(MessagesViewModel.Mailbox.INBOX); showMenu = false },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Archived") },
                            onClick = { vm.setMailbox(MessagesViewModel.Mailbox.ARCHIVED); showMenu = false },
                            leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Bin") },
                            onClick = { vm.setMailbox(MessagesViewModel.Mailbox.BIN); showMenu = false },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Spam and blocked") },
                            onClick = { showMenu = false; onOpenBlocklist() },
                            leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark all as read") },
                            onClick = { vm.markAllRead(); showMenu = false },
                            leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Messages settings") },
                            onClick = { showMenu = false; onOpenSettings() },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Help and feedback") },
                            onClick = { showMenu = false; onOpenHelp() },
                            leadingIcon = { Icon(Icons.Filled.HelpOutline, contentDescription = null) },
                        )
                    }
                }
            }

            // The bin is the one place a destructive action lives, so it is stated
            // rather than left to be discovered.
            if (mailbox == MessagesViewModel.Mailbox.BIN && visibleThreads.isNotEmpty()) {
                TextButton(
                    onClick = { vm.emptyBin() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Empty the bin") }
            }

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
                        placeholder = { Text(stringResource(R.string.search_senders_and_messages)) },
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

                    if (picked.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { vm.clearPicked() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                            }
                            Text(
                                if (picked.size == 1) "1 selected" else "${picked.size} selected",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.actOnPicked("read") }) { Text("Read") }
                            TextButton(onClick = { vm.actOnPicked("archive") }) { Text("Archive") }
                            TextButton(onClick = { vm.actOnPicked("bin") }) { Text("Bin") }
                        }
                        HorizontalDivider()
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
                        // Grouping is a display choice, so it is applied here
                        // rather than in the query. The threads arrive in one
                        // order either way; this only decides whether the user
                        // sees the seams.
                        //
                        // Flattened into one list of rows rather than nested
                        // `items` calls, so the long thread row below is written
                        // once instead of twice.
                        items(
                            threadRows(visibleThreads, groupByTime),
                            key = { row ->
                                when (row) {
                                    is ThreadRow.Header -> "header-${row.label}"
                                    is ThreadRow.Item -> row.thread.address
                                }
                            },
                        ) { row ->
                            if (row is ThreadRow.Header) {
                                Text(
                                    row.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp,
                                    ),
                                )
                                return@items
                            }
                            val t = (row as ThreadRow.Item).thread
                            val result = threadClassified[t.address]

                            // Swipe, doing whatever the user chose in settings.
                            //
                            // **This used to be three separate gesture handlers
                            // and it broke tapping.** `clickable` alongside a
                            // `pointerInput { detectTapGestures(onLongPress) }`
                            // does not give you "tap and long-press": the tap
                            // detector consumes the down and up events whether or
                            // not it has an `onTap`, so `clickable` never fired and
                            // a conversation could not be opened at all.
                            //
                            // `combinedClickable` is the one handler that owns both
                            // gestures properly. The drag is its own pointerInput
                            // and does not compete, because
                            // `detectHorizontalDragGestures` only claims the pointer
                            // once the touch slop is passed — a plain tap never
                            // reaches it.
                            var drag by remember(t.address) { mutableStateOf(0f) }
                            val threshold = 120f

                            Row(
                                Modifier.fillMaxWidth()
                                    .offset { IntOffset(drag.roundToInt(), 0) }
                                    .pointerInput(t.address, swipeAction) {
                                        if (swipeAction == "none") return@pointerInput
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (kotlin.math.abs(drag) >= threshold) {
                                                    vm.swipe(t.address)
                                                }
                                                drag = 0f
                                            },
                                            onDragCancel = { drag = 0f },
                                        ) { _, amount ->
                                            // Bounded, so a row cannot be dragged
                                            // off the screen and left there.
                                            drag = (drag + amount).coerceIn(-threshold * 1.6f, threshold * 1.6f)
                                        }
                                    }
                                    .combinedClickable(
                                        // While picking, a tap picks instead of
                                        // opening — otherwise selecting three
                                        // conversations would open three.
                                        onClick = {
                                            if (picked.isNotEmpty()) vm.togglePicked(t.address)
                                            else vm.openThread(t.address)
                                        },
                                        onClickLabel = "Open conversation",
                                        onLongClick = { vm.togglePicked(t.address) },
                                        onLongClickLabel = "Select conversation",
                                    )
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
                                        // Both are visible because both are states
                                        // the user set and would otherwise forget.
                                        if (t.address in pinned) {
                                            Icon(
                                                Icons.Filled.PushPin,
                                                contentDescription = "Pinned",
                                                modifier = Modifier.size(13.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (t.address in muted) {
                                            Icon(
                                                Icons.Filled.NotificationsOff,
                                                contentDescription = "Muted",
                                                modifier = Modifier.size(13.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
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
                                        Text(stringResource(R.string.contains_a_suspicious_link),
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
                Text(
                    PhoneUtils.pretty(current),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showThreadSearch = !showThreadSearch; vm.setThreadQuery("") }) {
                    Icon(
                        if (showThreadSearch) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (showThreadSearch) "Close search" else "Search this conversation",
                    )
                }
                IconButton(onClick = { exportConversation(context, current, messages) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Export conversation")
                }
            }

            if (showThreadSearch) {
                OutlinedTextField(
                    value = threadQuery,
                    onValueChange = { vm.setThreadQuery(it) },
                    placeholder = { Text("Search this conversation") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
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
                Text(stringResource(R.string.sending_is_off_sms_permission_is_not_granted),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ---- text-selection mode ----
            //
            // Shown because the mode changes what a long-press does. A mode the
            // user cannot see is a mode they get stuck in and blame the app for.
            if (textSelectFor != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { textSelectFor = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Leave text selection")
                    }
                    Text(
                        "Long-press the text to select part of it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
            }

            // ---- selection bar ----
            if (selectedIds.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.clearSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                    }
                    Text(
                        if (selectedIds.size == 1) "1 selected" else "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        copyToClipboard(context, vm.selectedMessages().joinToString("\n") { it.body })
                        vm.showStatus("Copied.")
                        vm.clearSelection()
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        forward(context, vm.selectedMessages().joinToString("\n") { it.body })
                        vm.clearSelection()
                    }) { Text("Forward") }
                    TextButton(onClick = {
                        vm.selectedMessages().forEach { vm.deleteMessage(context, it) }
                        vm.clearSelection()
                    }) { Text("Delete") }
                }
                HorizontalDivider()
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                // Flattened so a date separator can sit between days. `items` over
                // a list of either-a-day-or-a-message keeps the key stable and
                // means the bubble is written once.
                items(
                    threadLines(
                        // A search inside a conversation filters the messages, not
                        // the conversations — and it keeps the date separators
                        // honest, because they are computed after the filter.
                        if (threadQuery.isBlank()) messages
                        else messages.filter { it.body.contains(threadQuery, ignoreCase = true) },
                    ),
                    key = { line ->
                        when (line) {
                            is ThreadLine.Day -> "day-${line.label}"
                            is ThreadLine.Message -> "msg-${line.sms.id}"
                        }
                    },
                ) { line ->
                    if (line is ThreadLine.Day) {
                        Text(
                            line.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                        return@items
                    }

                    val m = (line as ThreadLine.Message).sms
                    val incoming = m.type == Telephony.Sms.MESSAGE_TYPE_INBOX
                    val chosen = m.id in selectedIds

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (incoming) Arrangement.Start else Arrangement.End,
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                        Surface(
                            color = when {
                                chosen -> MaterialTheme.colorScheme.primaryContainer
                                incoming -> MaterialTheme.colorScheme.surface
                                else -> MaterialTheme.colorScheme.primary
                            },
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.combinedClickable(
                                // In selection mode a tap adds to the set; out of
                                // it, a tap does nothing — which is what a tap on a
                                // bubble does in every messaging app.
                                onClick = { if (selectedIds.isNotEmpty()) vm.toggleSelected(m.id) },
                                onLongClick = { vm.toggleSelected(m.id) },
                                onLongClickLabel = "Select message",
                            ),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                // **Partial copy, restored.**
                                //
                                // v0.50.0 removed this to make multi-select work:
                                // SelectionContainer needs long-press to select
                                // text and multi-select needs it to select the
                                // message, and one gesture cannot do both. Dropping
                                // it silently also dropped something that was asked
                                // for from the start — "copy the whole message or a
                                // part of it from the middle".
                                //
                                // So it is a mode rather than a gesture. Choosing
                                // "Select text" puts this one bubble into a
                                // SelectionContainer; every other bubble keeps the
                                // long-press that multi-select uses. The two
                                // features stop competing because they no longer
                                // happen at the same time.
                                val bodyColor = when {
                                    chosen -> MaterialTheme.colorScheme.onPrimaryContainer
                                    incoming -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onPrimary
                                }
                                if (textSelectFor == m.id) {
                                    SelectionContainer {
                                        Text(m.body, color = bodyColor)
                                    }
                                } else {
                                    Text(m.body, color = bodyColor)
                                }
                                // The state of an outgoing message, from the
                                // tracker — which already knew and never said.
                                if (vm.isStarred(m.id)) {
                                    Spacer(Modifier.height(2.dp))
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = "Starred",
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                if (!incoming) {
                                    val state = deliveryStateFor(m)
                                    if (state != null) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            state,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedIds.isEmpty()) {
                            IconButton(
                                onClick = { actionTarget = m },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "Message options",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        }
                    }
                }
            }

            // ---- the message being replied to ----
            replyTo?.let { quoted ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    ) {
                        Text(
                            "Replying",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            quoted.body.take(80),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.clearReply() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel reply")
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
                    placeholder = { Text(stringResource(R.string.message)) },
                )
                IconButton(onClick = { vm.send(context, current, draft); draft = "" }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }

            // The counter, when it is worth showing. Silent until the message is
            // long enough to be more than one part, because a permanent "160 left"
            // under every short message is noise.
            val info = vm.partInfo(draft)
            if (info.parts > 1 || info.left <= 20) {
                Text(
                    buildString {
                        append("${info.length} characters")
                        if (info.parts > 1) append(" · ${info.parts} messages")
                        if (info.unicode) append(" · Unicode, so the limit is lower")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (info.parts > 1) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
        }
    }

    // ---- per-message actions ----
    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(stringResource(R.string.message)) },
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
                    MessageAction(if (vm.isStarred(target.id)) "Remove star" else "Star") {
                        vm.star(target.id)
                        actionTarget = null
                    }
                    MessageAction("Reply") {
                        vm.setReplyTo(target)
                        actionTarget = null
                    }
                    MessageAction("Select text") {
                        // Enters text-selection mode for this bubble. The system
                        // handles appear on long-press, and the selection can be
                        // copied with the usual handles — which is what "copy a
                        // part from the middle" means.
                        textSelectFor = target.id
                        actionTarget = null
                    }
                    MessageAction("Select") {
                        // Enters selection mode with this message chosen, which is
                        // what the old "Select part of the text" promised and could
                        // not deliver: it asked the user to long-press text that
                        // the app had already taken the long-press away from.
                        vm.toggleSelected(target.id)
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
                TextButton(onClick = { actionTarget = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    // ---- conversation options ----
    threadMenuFor?.let { address ->
        val bubbled = vm.isBubbled(address)
        AlertDialog(
            onDismissRequest = { threadMenuFor = null },
            title = { Text(SenderId.displayName(address)) },
            text = {
                Column {
                    MessageAction("Open") { threadMenuFor = null; vm.openThread(address) }
                    MessageAction(if (vm.isPinned(address)) "Unpin" else "Pin to top") {
                        threadMenuFor = null
                        vm.pin(address)
                    }
                    MessageAction(if (vm.isMuted(address)) "Unmute" else "Mute") {
                        threadMenuFor = null
                        vm.mute(address)
                    }
                    MessageAction(
                        if (address in boxes.silent) "Turn sound back on" else "Silence",
                    ) {
                        threadMenuFor = null
                        vm.silence(address)
                    }
                    MessageAction("Archive") { threadMenuFor = null; vm.archive(address) }
                    if (bubbled) {
                        MessageAction("Stop bubbling") { threadMenuFor = null; vm.toggleBubbled(address) }
                    } else {
                        MessageAction("Allow as a bubble") { threadMenuFor = null; vm.toggleBubbled(address) }
                    }
                    MessageAction("Move to bin") { threadMenuFor = null; vm.bin(address) }
                    MessageAction("Delete from device…") {
                        threadMenuFor = null
                        confirmThreadDelete = address
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { threadMenuFor = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    confirmThreadDelete?.let { address ->
        AlertDialog(
            onDismissRequest = { confirmThreadDelete = null },
            title = { Text(stringResource(R.string.delete_this_thread)) },
            text = {
                Text(
                    "Android only lets the default messaging app delete messages from the " +
                        "phone. If Kavach cannot, the thread will be hidden inside Kavach " +
                        "instead — nothing is lost from your phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmThreadDelete = null; vm.deleteThread(context, address) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmThreadDelete = null }) { Text(stringResource(R.string.cancel)) }
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
                Text(stringResource(R.string.the_code_is_hidden_here_on_purpose_so_it_cannot_be_r),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = { onCopyOtp(result.otpCode) }) { Text(stringResource(R.string.copy_code)) }
            }

            if (isBad) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onReport) { Text(stringResource(R.string.report_and_block)) }
                    OutlinedButton(onClick = onBlock) { Text(stringResource(R.string.block_sender)) }
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
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


/**
 * Split threads into Today / Yesterday / Older.
 *
 * Days are compared by calendar date, not by a 24-hour window: something sent at
 * 11pm is "Yesterday" at 1am, not "23 hours ago" — which is what a millisecond
 * difference would call it.
 */
private fun bucketByDay(
    threads: List<SmsReader.Thread>,
): List<Pair<String, List<SmsReader.Thread>>> {
    val today = java.util.Calendar.getInstance()
    val yesterday = (today.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }

    fun sameDay(cal: java.util.Calendar, at: Long): Boolean {
        val other = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return cal.get(java.util.Calendar.YEAR) == other.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == other.get(java.util.Calendar.DAY_OF_YEAR)
    }

    val buckets = linkedMapOf<String, MutableList<SmsReader.Thread>>()
    threads.forEach { thread ->
        val label = when {
            sameDay(today, thread.date) -> "Today"
            sameDay(yesterday, thread.date) -> "Yesterday"
            else -> "Older"
        }
        buckets.getOrPut(label) { mutableListOf() } += thread
    }
    // Fixed order, so an empty bucket never reshuffles the list.
    return listOf("Today", "Yesterday", "Older")
        .mapNotNull { label -> buckets[label]?.let { label to it.toList() } }
}


/** A row in the conversation list: a date seam, or a conversation. */
private sealed interface ThreadRow {
    data class Header(val label: String) : ThreadRow
    data class Item(val thread: SmsReader.Thread) : ThreadRow
}

/** Threads, with date seams when the user asked for them. */
private fun threadRows(
    threads: List<SmsReader.Thread>,
    grouped: Boolean,
): List<ThreadRow> {
    if (!grouped) return threads.map { ThreadRow.Item(it) }
    return bucketByDay(threads).flatMap { (label, bucket) ->
        listOf(ThreadRow.Header(label)) + bucket.map { ThreadRow.Item(it) }
    }
}


/**
 * The send sound.
 *
 * `FX_KEY_CLICK` is the platform's own acknowledgement tone, so it matches
 * whatever the phone already sounds like rather than adding a noise of Kavach's
 * own. Failure is ignored: a missing tone must never break a send.
 */
private fun playSendSound(context: Context) {
    runCatching {
        val manager = context.getSystemService(android.media.AudioManager::class.java) ?: return
        manager.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
    }
}


/** A row in a conversation: a day separator, or a message. */
private sealed interface ThreadLine {
    data class Day(val label: String) : ThreadLine
    data class Message(val sms: SmsReader.Sms) : ThreadLine
}

/**
 * Messages with a day separator between them.
 *
 * Compared by calendar date rather than by a 24-hour window, so something sent at
 * 11pm reads as "Yesterday" at 1am rather than as "23 hours ago".
 */
private fun threadLines(messages: List<SmsReader.Sms>): List<ThreadLine> {
    val out = mutableListOf<ThreadLine>()
    var lastLabel: String? = null
    val today = java.util.Calendar.getInstance()
    val yesterday = (today.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }

    messages.forEach { sms ->
        val at = java.util.Calendar.getInstance().apply { timeInMillis = sms.date }
        fun sameDay(cal: java.util.Calendar) =
            cal.get(java.util.Calendar.YEAR) == at.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == at.get(java.util.Calendar.DAY_OF_YEAR)

        val label = when {
            sameDay(today) -> "Today"
            sameDay(yesterday) -> "Yesterday"
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(sms.date))
        }
        if (label != lastLabel) {
            out += ThreadLine.Day(label)
            lastLabel = label
        }
        out += ThreadLine.Message(sms)
    }
    return out
}


/**
 * Export a conversation as a text file, through the system share sheet.
 *
 * A plain transcript rather than a database dump, because the reason to export a
 * conversation is to read it or send it to someone — and a format only Kavach can
 * open would defeat both. The file goes to the app's cache, and the share sheet
 * decides where it ends up; Kavach never learns that.
 */
private fun exportConversation(
    context: Context,
    address: String,
    messages: List<SmsReader.Sms>,
) {
    if (messages.isEmpty()) return
    runCatching {
        val who = SenderId.displayName(address).ifBlank { PhoneUtils.pretty(address) }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = who.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "conversation" }
        val file = File(dir, "$safe-$stamp.txt")

        val format = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault())
        file.bufferedWriter().use { out ->
            out.appendLine("Conversation with $who")
            out.appendLine("Exported ${format.format(java.util.Date())}")
            out.appendLine()
            messages.forEach { sms ->
                val from = if (sms.type == Telephony.Sms.MESSAGE_TYPE_INBOX) who else "You"
                out.appendLine("[${format.format(java.util.Date(sms.date))}] $from")
                out.appendLine(sms.body)
                out.appendLine()
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Conversation with $who")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export conversation"))
    }
}
