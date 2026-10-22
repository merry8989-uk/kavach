package com.kavach.caller.ui.screens.dialer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.local.entity.PhoneNumber
import com.kavach.caller.data.local.entity.SpeedDialEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.data.repo.ContactDraft
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.repo.SpeedDialRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.domain.usecase.IdentifyCallerUseCase
import com.kavach.caller.domain.usecase.SaveContactUseCase
import com.kavach.caller.domain.usecase.SearchContactsByT9UseCase
import com.kavach.caller.ui.components.CallerLookupDialog
import com.kavach.caller.ui.screens.contacts.ContactEditScreen
import com.kavach.caller.ui.screens.contacts.MonogramAvatar
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import com.kavach.caller.util.holds

/** Conventional dialer green — deliberately fixed so it reads as "call" in every theme. */
private val CallGreen = Color(0xFF8FE39B)
private val CallGreenContent = Color(0xFF0B2B12)

@HiltViewModel
class DialerViewModel @Inject constructor(
    private val identifyCaller: IdentifyCallerUseCase,
    private val searchContactsByT9: SearchContactsByT9UseCase,
    private val saveContactUseCase: SaveContactUseCase,
    private val callLogRepository: CallLogRepository,
    private val contactRepository: ContactRepository,
    private val speedDialRepository: SpeedDialRepository,
) : ViewModel() {

    /** All contacts, for the "Add to a contact" picker. */
    val contacts = contactRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Speed-dial slots 2-9 (F1.6). */
    val speedDial = speedDialRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun assignSpeedDial(slot: Int, number: String, label: String) = viewModelScope.launch {
        speedDialRepository.assign(slot, number, label)
    }

    suspend fun identify(number: String): CallerIdentity? =
        if (number.length >= 3) identifyCaller(number) else null

    suspend fun searchT9(digits: String): List<ContactEntity> = searchContactsByT9(digits)

    /**
     * One result list for whatever is typed: saved contacts *and* recent calls.
     *
     * Deduplicated by subscriber key. When a number is both, the row is built
     * from the recent call (its timestamp is the useful part) while the name
     * still comes from the saved contact, because a name the user chose beats
     * whatever the call log holds.
     */
    suspend fun search(input: String): List<DialerMatch> {
        val digits = input.filter { it.isDigit() }
        if (digits.length < 2) return emptyList()

        val t9 = if (input.all { it.isDigit() }) searchContactsByT9(digits) else emptyList()
        val byNumber = contactRepository.findMatchingNumber(digits)
        val contacts = (t9 + byNumber).distinctBy { it.id }

        val recents = callLogRepository.recentMatching(digits)

        val merged = LinkedHashMap<String, DialerMatch>()
        contacts.forEach { contact ->
            val number = contact.numbers.firstOrNull()?.number ?: return@forEach
            val key = PhoneUtils.subscriberKey(number)
            if (key.isEmpty()) return@forEach
            merged[key] = DialerMatch(
                number = number,
                name = contact.displayName,
                source = MatchSource.CONTACT,
                contactId = contact.id,
            )
        }
        recents.forEach { call ->
            val key = PhoneUtils.subscriberKey(call.number)
            if (key.isEmpty()) return@forEach
            val existing = merged[key]
            merged[key] = DialerMatch(
                number = call.number,
                name = existing?.name ?: call.name,
                source = if (existing != null) MatchSource.BOTH else MatchSource.RECENT,
                lastCallAt = call.timestamp,
                lastCallType = call.type,
                contactId = existing?.contactId,
            )
        }

        // Most recent interaction first; contacts never called sort after them.
        return merged.values.sortedWith(
            compareByDescending<DialerMatch> { it.lastCallAt ?: 0L }
                .thenBy { (it.name ?: it.number).lowercase() },
        )
    }

    suspend fun lastCall(number: String): CallLogEntity? = callLogRepository.lastCallFor(number)

    fun saveContact(draft: ContactDraft) = viewModelScope.launch { saveContactUseCase(draft) }
}

private data class DialKey(
    val digit: String,
    val letters: String = "",
    val longPress: String? = null,
)

private val DialKeys = listOf(
    DialKey("1"), DialKey("2", "ABC"), DialKey("3", "DEF"),
    DialKey("4", "GHI"), DialKey("5", "JKL"), DialKey("6", "MNO"),
    DialKey("7", "PQRS"), DialKey("8", "TUV"), DialKey("9", "WXYZ"),
    DialKey("*", longPress = ","), DialKey("0", longPress = "+"), DialKey("#", longPress = ";"),
)

private data class SimOption(val label: String, val handle: PhoneAccountHandle?)

private fun activeSims(context: Context): List<SimOption> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED
    ) return emptyList()
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
    return runCatching {
        val subs = context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList ?: emptyList()
        telecom.callCapablePhoneAccounts.map { handle ->
            val info = subs.firstOrNull { it.subscriptionId.toString() == handle.id }
            SimOption(
                label = info?.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: telecom.getPhoneAccount(handle)?.label?.toString()
                    ?: "SIM",
                handle = handle,
            )
        }
    }.getOrDefault(emptyList())
}

@Composable
fun DialerScreen(
    onOpenNumber: (String) -> Unit = {},
    vm: DialerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var identity by remember { mutableStateOf<CallerIdentity?>(null) }
    var lastCallAt by remember { mutableStateOf<Long?>(null) }
    var results by remember { mutableStateOf<List<DialerMatch>>(emptyList()) }
    var editorDraft by remember { mutableStateOf<ContactDraft?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var showVideoInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var lookupNumber by remember { mutableStateOf<String?>(null) }
    val sims = remember { activeSims(context) }
    var selectedSim by rememberSaveable { mutableStateOf(0) }

    val contacts by vm.contacts.collectAsState()
    val speedDials by vm.speedDial.collectAsState()
    var assignSlot by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(input) {
        identity = vm.identify(input)
        lastCallAt = vm.lastCall(input)?.timestamp
        results = vm.search(input)
    }

    fun placeCall(raw: String) {
        if (raw.isBlank()) return
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val uri = Uri.parse("tel:${PhoneUtils.toE164(raw)}")
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        if (canCall && sims.size > 1 && selectedSim in sims.indices) {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sims[selectedSim].handle)
        }
        runCatching { context.startActivity(intent) }
    }

    // Contact editor, opened from the dialer with the typed number.
    val draft = editorDraft
    if (draft != null) {
        ContactEditScreen(
            initial = null,
            initialDraft = draft,
            onCancel = { editorDraft = null },
            onSave = { saved ->
                vm.saveContact(saved)
                editorDraft = null
            },
            onDelete = { },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ---- Top: who is this, and what can I do with them ----
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (input.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = identity?.name ?: "Not in your contacts",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Tapping the card opens the full profile for this
                        // number rather than doing nothing.
                        .clickable { onOpenNumber(input) },
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MonogramAvatar(identity?.name ?: "?", size = 40)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                PhoneUtils.pretty(input),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                lastCallLabel(lastCallAt),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.tap_for_full_details),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { placeCall(input) }) {
                            Icon(Icons.Filled.Phone, contentDescription = "Call back")
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                if (identity?.name == null) {
                    DialerAction(Icons.Filled.PersonAdd, "Create new contact") {
                        editorDraft = ContactDraft(numbers = listOf(PhoneNumber("Mobile", input)))
                    }
                    DialerAction(Icons.Filled.GroupAdd, "Add to a contact") {
                        showAddPicker = true
                    }
                }
                DialerAction(Icons.Filled.Videocam, "Video call") { showVideoInfo = true }
                DialerAction(Icons.Filled.Sms, "Send a message") { sendMessage(context, input) }

                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.matches),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(results, key = { PhoneUtils.subscriberKey(it.number) }) { match ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        input = PhoneUtils.normalize(match.number).trimStart('+')
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MonogramAvatar(match.displayName, size = 38)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        match.displayName,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                    )
                                    Text(
                                        buildString {
                                            append(PhoneUtils.pretty(match.number))
                                            match.lastCallAt?.let { at ->
                                                append(" · ")
                                                append(matchCallLabel(match.lastCallType, at))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                MatchBadge(match.source)
                            }
                        }
                    }
                }
            }
        }

        // ---- Bottom: number display + keypad + call ----
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // Favourites strip — one tap to call the people you ring most.
            val favourites = contacts.filter { it.isFavourite }
            if (input.isEmpty() && favourites.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(favourites, key = { it.id }) { c ->
                        Column(
                            Modifier.width(60.dp).clickable {
                                c.numbers.firstOrNull()?.number?.let { placeCall(it) }
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            MonogramAvatar(c.displayName, size = 46)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                c.displayName.substringBefore(' '),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.paste)) },
                            onClick = {
                                menuOpen = false
                                input += pasteFromClipboard(context)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_number)) },
                            onClick = {
                                menuOpen = false
                                copyToClipboard(context, input)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_2_sec_pause)) },
                            onClick = { menuOpen = false; input += "," },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.add_wait)) },
                            onClick = { menuOpen = false; input += ";" },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.find_caller_s_name)) },
                            enabled = input.isNotBlank(),
                            onClick = {
                                menuOpen = false
                                if (input.isNotBlank()) lookupNumber = input
                            },
                        )
                    }
                }

                Text(
                    text = if (input.isEmpty()) "" else PhoneUtils.pretty(input),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )

                // Tap deletes one digit; holding keeps deleting until release.
                var backspaceHeld by remember { mutableStateOf(false) }
                LaunchedEffect(backspaceHeld) {
                    if (!backspaceHeld) return@LaunchedEffect
                    while (input.isNotEmpty()) {
                        delay(BACKSPACE_REPEAT_MS)
                        if (input.isEmpty()) break
                        input = input.dropLast(1)
                    }
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (input.isNotEmpty()) input = input.dropLast(1)
                                    backspaceHeld = true
                                    tryAwaitRelease()
                                    backspaceHeld = false
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
                }
            }

            if (sims.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    sims.forEachIndexed { index, sim ->
                        FilterChip(
                            selected = selectedSim == index,
                            onClick = { selectedSim = index },
                            label = { Text(sim.label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Column(Modifier.fillMaxWidth()) {
                DialKeys.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { key ->
                            DialKeyButton(
                                key = key,
                                modifier = Modifier.weight(1f),
                                onTap = { input += key.digit },
                                onLongPress = {
                                    val slot = key.digit.toIntOrNull()
                                    val assigned = speedDials.firstOrNull { it.slot == slot }
                                    when {
                                        key.digit == "1" -> voiceMailNumber(context)?.let { placeCall(it) }
                                        slot != null && slot in 2..9 && assigned != null ->
                                            placeCall(assigned.number)
                                        slot != null && slot in 2..9 -> assignSlot = slot
                                        key.longPress != null -> input += key.longPress
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { placeCall(input) },
                    enabled = input.isNotBlank(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CallGreen,
                        contentColor = CallGreenContent,
                    ),
                    modifier = Modifier.height(52.dp).widthIn(min = 150.dp),
                ) {
                    Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.call), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAddPicker) {
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text(stringResource(R.string.add_to_a_contact)) },
            text = {
                if (contacts.isEmpty()) {
                    Text(stringResource(R.string.you_have_no_contacts_yet_create_one_first))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(contacts, key = { it.id }) { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                supportingContent = {
                                    Text(c.numbers.firstOrNull()?.number ?: "")
                                },
                                modifier = Modifier.clickable {
                                    showAddPicker = false
                                    editorDraft = ContactDraft.from(c).copy(
                                        numbers = c.numbers + PhoneNumber("Mobile", input),
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddPicker = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    assignSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { assignSlot = null },
            title = { Text(stringResource(R.string.speed_dial_slot, slot)) },
            text = {
                if (contacts.isEmpty()) {
                    Text(stringResource(R.string.you_have_no_contacts_to_assign_yet))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(contacts, key = { it.id }) { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                supportingContent = { Text(c.numbers.firstOrNull()?.number ?: "") },
                                modifier = Modifier.clickable {
                                    val number = c.numbers.firstOrNull()?.number
                                    if (!number.isNullOrBlank()) {
                                        vm.assignSpeedDial(slot, number, c.displayName)
                                    }
                                    assignSlot = null
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assignSlot = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    lookupNumber?.let { number ->
        CallerLookupDialog(number = number, onDismiss = { lookupNumber = null })
    }

    if (showVideoInfo) {
        AlertDialog(
            onDismissRequest = { showVideoInfo = false },
            title = { Text(stringResource(R.string.video_calling)) },
            text = {
                Text(
                    "Two-way video calls need Kavach to hold the default-dialer role and the " +
                        "carrier to support video calling. That path isn't implemented, so this " +
                        "option is unavailable rather than pretending to work."
                )
            },
            confirmButton = {
                TextButton(onClick = { showVideoInfo = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

@Composable
private fun DialerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun DialKeyButton(
    key: DialKey,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(66.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(key.digit) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                key.digit,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when {
                key.letters.isNotEmpty() -> Text(
                    key.letters,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The 1 key shows a voicemail glyph, like a stock dialer.
                key.digit == "1" -> Icon(
                    Icons.Filled.Voicemail,
                    contentDescription = "Voicemail",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun lastCallLabel(timestamp: Long?): String {
    if (timestamp == null) return "No recent calls"
    val formatted = SimpleDateFormat("dd MMM, HH:mm", Locale("en", "IN")).format(Date(timestamp))
    return "Last call $formatted"
}

private fun sendMessage(context: Context, number: String) {
    val intent = Intent(
        Intent.ACTION_SENDTO,
        Uri.parse("smsto:${PhoneUtils.toE164(number)}"),
    )
    runCatching { context.startActivity(intent) }
}

/** How fast the backspace key repeats while it is held. */
private const val BACKSPACE_REPEAT_MS = 70L

@Composable
private fun MatchBadge(source: MatchSource) {
    val label = when (source) {
        MatchSource.CONTACT -> "Contact"
        MatchSource.RECENT -> "Recent"
        MatchSource.BOTH -> "Contact · Recent"
    }
    val container = when (source) {
        MatchSource.CONTACT -> MaterialTheme.colorScheme.primaryContainer
        MatchSource.RECENT -> MaterialTheme.colorScheme.tertiaryContainer
        MatchSource.BOTH -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun matchCallLabel(type: Int?, at: Long): String {
    val kind = when (type) {
        android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
        android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
        else -> "Call"
    }
    val when_ = android.text.format.DateUtils.getRelativeTimeSpanString(
        at,
        System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    return "$kind · $when_"
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("phone number", text))
}

private fun pasteFromClipboard(context: Context): String {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val clip = manager.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    val text = clip.getItemAt(0).coerceToText(context).toString()
    return text.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
}

private fun voiceMailNumber(context: Context): String? {
    // Reading the voicemail number is gated on READ_PHONE_STATE, and asking
    // without it throws rather than returning null.
    if (!(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)) return null
    return runCatching {
        context.getSystemService(TelephonyManager::class.java)?.voiceMailNumber
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
