package com.kavach.caller.ui.screens.contacts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.repo.CallLogRepository
import com.kavach.caller.ui.components.CallerLookupDialog
import com.kavach.caller.ui.components.FavouriteStar
import com.kavach.caller.ui.components.InfoRow
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.PillTag
import com.kavach.caller.ui.components.SectionHeader
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import com.kavach.caller.data.calendar.CalendarGateway
import com.kavach.caller.data.repo.ReminderRepository
import com.kavach.caller.ui.screens.reminders.ReminderSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.kavach.caller.domain.toVCard
import java.io.File
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Alarm

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val callLogRepository: CallLogRepository,
    private val reminders: ReminderRepository,
    private val calendar: CalendarGateway,
) : ViewModel() {

    private val _recent = MutableStateFlow<List<CallLogEntity>>(emptyList())
    val recent: StateFlow<List<CallLogEntity>> = _recent.asStateFlow()

    var savingReminder by mutableStateOf(false)
        private set
    var reminderMessage by mutableStateOf<String?>(null)
        private set

    /** True only when a calendar exists that can actually be written to. */
    fun calendarAvailable(): Boolean = runCatching { calendar.canWrite() }.getOrDefault(false)

    fun calendarName(): String? =
        runCatching { calendar.writableCalendars().firstOrNull()?.displayName }.getOrNull()

    /**
     * Save a reminder and report where it ended up.
     *
     * The message says which of the two happened, because "I set a reminder and
     * nothing appeared in my calendar" is the failure this avoids.
     */
    fun addReminder(
        number: String,
        title: String,
        note: String?,
        dueAt: Long,
        toCalendar: Boolean,
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        savingReminder = true
        when (val outcome = reminders.add(number, title, note, dueAt, toCalendar)) {
            is ReminderRepository.Outcome.Saved ->
                reminderMessage = if (outcome.inCalendar) {
                    "Reminder set, and added to ${outcome.calendarName ?: "your calendar"}."
                } else {
                    "Reminder set in Kavach."
                }
            is ReminderRepository.Outcome.SavedLocalOnly ->
                // Saved anyway, and the reason is said rather than swallowed.
                reminderMessage = "Saved in Kavach. ${outcome.reason}"
        }
        savingReminder = false
        onDone()
    }

    fun dismissReminderMessage() { reminderMessage = null }

    fun load(contact: ContactEntity) {
        val number = contact.numbers.firstOrNull()?.number ?: return
        viewModelScope.launch {
            _recent.value = runCatching { callLogRepository.recentWith(number) }
                .getOrDefault(emptyList())
        }
    }
}

/**
 * Contact detail (F2.7): the full profile, quick actions, and the recent
 * interaction history with this person.
 */
@Composable
fun ContactDetailScreen(
    contact: ContactEntity,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (ContactEntity) -> Unit,
    onToggleFavourite: (ContactEntity) -> Unit,
    /** Open the calling-card editor for this person. */
    onOpenCallingCard: (String, String?) -> Unit = { _, _ -> },
    onOpenReminders: () -> Unit = {},
    vm: ContactDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val recent by vm.recent.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var lookupNumber by remember { mutableStateOf<String?>(null) }
    var reminderFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(contact.id) { vm.load(contact) }

    reminderFor?.let { number ->
        ReminderSheet(
            number = number,
            contactName = contact.displayName,
            calendarAvailable = vm.calendarAvailable(),
            calendarName = vm.calendarName(),
            submitting = vm.savingReminder,
            onDismiss = { reminderFor = null },
            onSave = { title, note, dueAt, toCalendar ->
                vm.addReminder(number, title, note, dueAt, toCalendar) { reminderFor = null }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.weight(1f))
            FavouriteStar(
                isFavourite = contact.isFavourite,
                onToggle = { onToggleFavourite(contact) },
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---- identity ----
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MonogramAvatar(contact.displayName, size = 84)
                Spacer(Modifier.height(12.dp))
                Text(
                    contact.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                contact.company?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (contact.tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        contact.tags.take(4).forEach { PillTag(it) }
                    }
                }
            }

            // ---- quick actions ----
            val primaryNumber = contact.numbers.firstOrNull()?.number
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    icon = Icons.Filled.Call,
                    label = "Call",
                    enabled = primaryNumber != null,
                    modifier = Modifier.weight(1f),
                ) { primaryNumber?.let { placeCall(context, it) } }
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Message,
                    label = "Message",
                    enabled = primaryNumber != null,
                    modifier = Modifier.weight(1f),
                ) { primaryNumber?.let { sendMessage(context, it) } }
                ActionButton(
                    icon = Icons.Filled.Search,
                    label = "Look up",
                    enabled = primaryNumber != null,
                    modifier = Modifier.weight(1f),
                ) { primaryNumber?.let { lookupNumber = it } }
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    modifier = Modifier.weight(1f),
                ) { shareContact(context, contact) }
                ActionButton(
                    icon = Icons.Filled.Brush,
                    label = "Calling card",
                    enabled = primaryNumber != null,
                    modifier = Modifier.weight(1f),
                ) { primaryNumber?.let { onOpenCallingCard(it, contact.displayName) } }
                ActionButton(
                    icon = Icons.Filled.Alarm,
                    label = "Remind",
                    enabled = primaryNumber != null,
                    modifier = Modifier.weight(1f),
                ) { primaryNumber?.let { reminderFor = it } }
            }

            vm.reminderMessage?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- numbers ----
            SectionHeader("Numbers")
            KavachCard {
                contact.numbers.forEachIndexed { index, number ->
                    if (index > 0) HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    )
                    InfoRow(
                        icon = Icons.Filled.Phone,
                        label = number.label.ifBlank { "Phone" },
                        value = PhoneUtils.pretty(number.number),
                        onClick = { placeCall(context, number.number) },
                    )
                }
                if (contact.numbers.isEmpty()) {
                    Text(stringResource(R.string.no_numbers_saved),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- work ----
            if (!contact.company.isNullOrBlank() || !contact.jobTitle.isNullOrBlank()) {
                SectionHeader("Work")
                KavachCard {
                    InfoRow(Icons.Filled.Business, "Company", contact.company)
                    InfoRow(Icons.Filled.Business, "Job title", contact.jobTitle)
                }
            }

            // ---- notes ----
            if (!contact.notes.isNullOrBlank()) {
                SectionHeader("Notes")
                KavachCard {
                    InfoRow(Icons.Filled.Notes, "Notes", contact.notes)
                }
            }

            // ---- ringtone ----
            SectionHeader("Ringtone")
            KavachCard {
                InfoRow(
                    icon = Icons.Filled.MusicNote,
                    label = "Custom ringtone",
                    value = if (contact.ringtoneUri.isNullOrBlank()) {
                        "System default"
                    } else {
                        "Custom (change in Edit)"
                    },
                )
            }

            // ---- recent activity ----
            SectionHeader("Recent activity")
            KavachCard {
                if (recent.isEmpty()) {
                    Text(stringResource(R.string.no_calls_recorded_with_this_contact_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recent.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        )
                        ActivityRow(entry)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    lookupNumber?.let { number ->
        CallerLookupDialog(number = number, onDismiss = { lookupNumber = null })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_contact)) },
            text = {
                Text(
                    "This removes ${contact.displayName} from Kavach and, if it came from your " +
                        "phone's contacts, from the device too."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(contact) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    // Defaulted rather than required: sharing a contact needs nothing to be
    // available, and forcing a caller to write `enabled = true` says otherwise.
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun ActivityRow(entry: CallLogEntity) {
    val (icon, tint) = when (entry.type) {
        CallLog.Calls.MISSED_TYPE -> Icons.Filled.CallMissed to MaterialTheme.colorScheme.error
        CallLog.Calls.OUTGOING_TYPE -> Icons.Filled.CallMade to MaterialTheme.colorScheme.primary
        else -> Icons.Filled.CallReceived to MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (entry.type) {
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    else -> "Call"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                formatWhen(entry.timestamp) +
                    if (entry.durationSec > 0) " · ${PhoneUtils.formatDuration(entry.durationSec)}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatWhen(timestamp: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale("en", "IN")).format(Date(timestamp))

private fun placeCall(context: Context, number: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${PhoneUtils.toE164(number)}"))
    runCatching { context.startActivity(intent) }
}

private fun sendMessage(context: Context, number: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${PhoneUtils.toE164(number)}"))
    runCatching { context.startActivity(intent) }
}


/**
 * Share a contact as a vCard.
 *
 * The file is written rather than sent as text, because a vCard is what the
 * receiving app can actually import — a name and a number pasted into a chat is
 * something the recipient retypes by hand. Sharing goes through the system sheet,
 * so the user picks the destination and Kavach never learns it.
 */
fun shareContact(context: Context, contact: ContactEntity) {
    runCatching {
        val dir = File(context.cacheDir, "shared-contacts").apply { mkdirs() }
        val safe = contact.displayName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "contact" }
        val file = File(dir, "$safe.vcf")
        file.writeText(contact.toVCard())

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcard"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, contact.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share contact"))
    }
}
