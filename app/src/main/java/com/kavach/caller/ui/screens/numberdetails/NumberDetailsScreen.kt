package com.kavach.caller.ui.screens.numberdetails

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
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
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.model.CallerIdentity
import com.kavach.caller.domain.usecase.IdentifyCallerUseCase
import com.kavach.caller.ui.components.CallerLookupDialog
import com.kavach.caller.ui.components.InfoRow
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import com.kavach.caller.ui.screens.contacts.Avatar
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Everything known about one number, in one place (F9.13).
 *
 * Reached by tapping a number anywhere — the dialer's identity card, a call-log
 * row, a recent call. It answers three questions: who is this, what do I already
 * know about them, and what have we said to each other.
 */
@HiltViewModel
class NumberDetailsViewModel @Inject constructor(
    private val identifyCaller: IdentifyCallerUseCase,
    private val contacts: ContactRepository,
    private val callLog: CallLogRepository,
    private val api: com.kavach.caller.data.remote.KavachApiClient,
    private val searchHistory: com.kavach.caller.data.repo.SearchHistoryRepository,
) : ViewModel() {

    private val _identity = MutableStateFlow<CallerIdentity?>(null)
    val identity: StateFlow<CallerIdentity?> = _identity.asStateFlow()

    private val _contact = MutableStateFlow<ContactEntity?>(null)
    val contact: StateFlow<ContactEntity?> = _contact.asStateFlow()

    private val _recent = MutableStateFlow<List<CallLogEntity>>(emptyList())
    val recent: StateFlow<List<CallLogEntity>> = _recent.asStateFlow()

    private val _loading = MutableStateFlow(true)

    var appealing by mutableStateOf(false)
        private set

    /** What the server said, in its own words — never a guessed success. */
    var appealMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Tell the server a number has been labelled wrongly.
     *
     * With no backend configured this cannot be filed, and the message says so
     * rather than pretending. A silent no-op would leave someone believing they
     * had appealed.
     */
    fun appeal(number: String) = viewModelScope.launch {
        appealing = true
        appealMessage = null
        val response = runCatching { api.submitAppeal(number) }.getOrNull()
        appealMessage = when {
            response == null ->
                "Could not file the appeal — no server is configured, or it could not be reached."
            response.accepted -> response.detail
            else -> "The server did not accept the appeal."
        }
        appealing = false
    }
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun load(number: String) = viewModelScope.launch {
        _loading.value = true
        val identity = identifyCaller(number)
        _identity.value = identity
        _contact.value = contacts.findByNumber(number)
        _recent.value = callLog.recentWith(number)

        // Recorded here rather than at the search box, because this is the point
        // at which the user actually looked the number up — a typed query that
        // was abandoned is not a lookup.
        searchHistory.record(
            number = number,
            name = identity?.name,
            spamScore = identity?.spamScore ?: 0,
        )
        _loading.value = false
    }
}

@Composable
fun NumberDetailsScreen(
    number: String,
    onBack: () -> Unit,
    onOpenContact: (ContactEntity) -> Unit = {},
    onAddContact: (String) -> Unit = {},
    vm: NumberDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val identity by vm.identity.collectAsState()
    val contact by vm.contact.collectAsState()
    val recent by vm.recent.collectAsState()
    val loading by vm.loading.collectAsState()
    var lookup by remember { mutableStateOf(false) }

    LaunchedEffect(number) { vm.load(number) }

    val primary = contact?.numbers?.firstOrNull()?.number ?: number

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(stringResource(R.string.number_details),
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
            // ---- who is this ----
            KavachCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (contact != null) {
                        Avatar(contact!!, size = 56)
                    } else {
                        Avatar(
                            ContactEntity(displayName = identity?.name ?: number, numbers = emptyList()),
                            size = 56,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            identity?.name ?: PhoneUtils.pretty(number),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            PhoneUtils.pretty(number),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SourceBadge(identity)
                            SpamBadge(identity)
                        }
                    }
                }

                if (identity?.isTentative == true) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This name came from the community, so treat it as a possibility " +
                            "rather than a fact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(Icons.Filled.Call, "Call", Modifier.weight(1f)) {
                        dial(context, primary)
                    }
                    ActionButton(
                        Icons.AutoMirrored.Filled.Message,
                        "Message",
                        Modifier.weight(1f),
                    ) { sms(context, primary) }
                    ActionButton(Icons.Filled.Search, "Look up", Modifier.weight(1f)) {
                        lookup = true
                    }
                }
            }

            // ---- the full contact profile, when we have one ----
            if (contact != null) {
                val c = contact!!
                SectionHeader("Contact")
                KavachCard {
                    if (c.company != null || c.jobTitle != null) {
                        InfoRow(label = "Company", value = listOfNotNull(c.company, c.jobTitle).joinToString(" · "))
                    }
                    c.numbers.forEachIndexed { index, entry ->
                        InfoRow(
                            label = entry.label ?: if (index == 0) "Primary" else "Number ${index + 1}",
                            value = PhoneUtils.pretty(entry.number),
                        )
                    }
                    if (c.notes != null) InfoRow(label = "Notes", value = c.notes)
                    if (c.tags.isNotEmpty()) InfoRow(label = "Tags", value = c.tags.joinToString(" · "))
                    InfoRow(
                        label = "Ringtone",
                        value = if (c.ringtoneUri.isNullOrBlank()) "System default" else "Custom",
                    )
                    InfoRow(label = "Favourite", value = if (c.isFavourite) "Yes" else "No")

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onOpenContact(c) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.open_full_contact)) }
                }
            } else {
                SectionHeader("Contact")
                KavachCard {
                    Text(stringResource(R.string.not_in_your_contacts),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onAddContact(number) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Contacts, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_to_contacts))
                    }
                }
            }

            // ---- what we know about the number itself ----
            SectionHeader("Number")
            KavachCard {
                InfoRow(label = "Line type", value = identity?.company ?: "Unknown")
                InfoRow(label = "Source", value = identitySourceLabel(identity))
                InfoRow(
                    label = "Confidence",
                    value = if (identity == null) "—" else "${(identity!!.confidence * 100).toInt()}%",
                )
                InfoRow(label = "Spam reports", value = "${identity?.reports ?: 0}")
                InfoRow(label = "Risk score", value = "${identity?.spamScore ?: 0} / 100")

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.appeal(primary) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.appealing,
                ) { Text("This is not spam") }
                vm.appealMessage?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- history with this number ----
            SectionHeader("Recent with this number")
            KavachCard {
                if (loading) {
                    Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (recent.isEmpty()) {
                    Text(stringResource(R.string.no_calls_recorded_yet_history_imports_from_your_devi),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recent.forEach { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(
                                entry.typeLabel(),
                                modifier = Modifier.width(78.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                SimpleDateFormat("dd MMM, HH:mm", Locale("en", "IN"))
                                    .format(Date(entry.timestamp)),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (entry.durationSec > 0) {
                                Text(
                                    PhoneUtils.formatDuration(entry.durationSec),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (lookup) {
        CallerLookupDialog(number = number, onDismiss = { lookup = false })
    }
}

@Composable
private fun SourceBadge(identity: CallerIdentity?) {
    val (text, container) = when (identity?.source) {
        CallerIdentity.Source.CONTACT -> "In your contacts" to MaterialTheme.colorScheme.primaryContainer
        CallerIdentity.Source.BUSINESS -> "Verified business" to MaterialTheme.colorScheme.primaryContainer
        CallerIdentity.Source.CLOUD -> "Community" to MaterialTheme.colorScheme.tertiaryContainer
        else -> "Unknown" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SpamBadge(identity: CallerIdentity?) {
    val score = identity?.spamScore ?: 0
    if (score < 20) return
    val (text, container) = when {
        score >= 70 -> "Spam" to MaterialTheme.colorScheme.errorContainer
        else -> "Possibly spam" to MaterialTheme.colorScheme.tertiaryContainer
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

private fun identitySourceLabel(identity: CallerIdentity?): String = when (identity?.source) {
    CallerIdentity.Source.CONTACT -> "Your contact"
    CallerIdentity.Source.BUSINESS -> "Verified business listing"
    CallerIdentity.Source.CLOUD -> "Community suggestion"
    CallerIdentity.Source.LOCAL_DB -> "Local heuristics only"
    else -> "Nothing on record"
}

private fun CallLogEntity.typeLabel(): String = when (type) {
    android.provider.CallLog.Calls.MISSED_TYPE -> "Missed"
    android.provider.CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
    android.provider.CallLog.Calls.INCOMING_TYPE -> "Incoming"
    else -> "Call"
}

private fun dial(context: Context, number: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun sms(context: Context, number: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
