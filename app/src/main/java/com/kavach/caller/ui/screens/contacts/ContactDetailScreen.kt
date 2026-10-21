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

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val callLogRepository: CallLogRepository,
) : ViewModel() {

    private val _recent = MutableStateFlow<List<CallLogEntity>>(emptyList())
    val recent: StateFlow<List<CallLogEntity>> = _recent.asStateFlow()

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
    vm: ContactDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val recent by vm.recent.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(contact.id) { vm.load(contact) }

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
                    Text(
                        "No numbers saved",
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
                    Text(
                        "No calls recorded with this contact yet.",
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete contact?") },
            text = {
                Text(
                    "This removes ${contact.displayName} from Kavach and, if it came from your " +
                        "phone's contacts, from the device too."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(contact) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
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
