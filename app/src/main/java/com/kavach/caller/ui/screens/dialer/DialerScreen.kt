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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.data.repo.ContactDraft
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.domain.usecase.IdentifyCallerUseCase
import com.kavach.caller.domain.usecase.SaveContactUseCase
import com.kavach.caller.domain.usecase.SearchContactsByT9UseCase
import com.kavach.caller.ui.screens.contacts.ContactEditScreen
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

/** Conventional dialer green — deliberately fixed so it reads as "call" in every theme. */
private val CallGreen = Color(0xFF8FE39B)
private val CallGreenContent = Color(0xFF0B2B12)

@HiltViewModel
class DialerViewModel @Inject constructor(
    private val identifyCaller: IdentifyCallerUseCase,
    private val searchContactsByT9: SearchContactsByT9UseCase,
    private val saveContactUseCase: SaveContactUseCase,
    callLogRepository: CallLogRepository,
    contactRepository: ContactRepository,
) : ViewModel() {

    /** All contacts, for the "Add to a contact" picker. */
    val contacts = contactRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun identify(number: String): CallerIdentity? =
        if (number.length >= 3) identifyCaller(number) else null

    suspend fun searchT9(digits: String): List<ContactEntity> = searchContactsByT9(digits)

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
fun DialerScreen(vm: DialerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var identity by remember { mutableStateOf<CallerIdentity?>(null) }
    var lastCallAt by remember { mutableStateOf<Long?>(null) }
    var matches by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    var editorDraft by remember { mutableStateOf<ContactDraft?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var showVideoInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val sims = remember { activeSims(context) }
    var selectedSim by rememberSaveable { mutableStateOf(0) }

    val contacts by vm.contacts.collectAsState()

    LaunchedEffect(input) {
        identity = vm.identify(input)
        lastCallAt = vm.lastCall(input)?.timestamp
        matches = if (input.isNotEmpty() && input.all { it.isDigit() }) vm.searchT9(input) else emptyList()
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
                    modifier = Modifier.fillMaxWidth(),
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

                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Contacts",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(matches, key = { it.id }) { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                supportingContent = {
                                    Text(c.numbers.firstOrNull()?.number ?: "")
                                },
                                modifier = Modifier.clickable {
                                    c.numbers.firstOrNull()?.number?.let { n ->
                                        input = PhoneUtils.normalize(n).trimStart('+')
                                    }
                                },
                            )
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
                            text = { Text("Paste") },
                            onClick = {
                                menuOpen = false
                                input += pasteFromClipboard(context)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy number") },
                            onClick = {
                                menuOpen = false
                                copyToClipboard(context, input)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Add 2-sec pause") },
                            onClick = { menuOpen = false; input += "," },
                        )
                        DropdownMenuItem(
                            text = { Text("Add wait") },
                            onClick = { menuOpen = false; input += ";" },
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

                IconButton(
                    onClick = { if (input.isNotEmpty()) input = input.dropLast(1) },
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
                                    when {
                                        key.digit == "1" -> voiceMailNumber(context)?.let { placeCall(it) }
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
                    Text("Call", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAddPicker) {
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text("Add to a contact") },
            text = {
                if (contacts.isEmpty()) {
                    Text("You have no contacts yet. Create one first.")
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
                TextButton(onClick = { showAddPicker = false }) { Text("Cancel") }
            },
        )
    }

    if (showVideoInfo) {
        AlertDialog(
            onDismissRequest = { showVideoInfo = false },
            title = { Text("Video calling") },
            text = {
                Text(
                    "Two-way video calls need Kavach to hold the default-dialer role and the " +
                        "carrier to support video calling. That path isn't implemented, so this " +
                        "option is unavailable rather than pretending to work."
                )
            },
            confirmButton = {
                TextButton(onClick = { showVideoInfo = false }) { Text("OK") }
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

private fun voiceMailNumber(context: Context): String? =
    runCatching {
        context.getSystemService(TelephonyManager::class.java)?.voiceMailNumber
    }.getOrNull()?.takeIf { it.isNotBlank() }
