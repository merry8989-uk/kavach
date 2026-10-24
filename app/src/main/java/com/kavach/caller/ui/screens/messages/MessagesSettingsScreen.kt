package com.kavach.caller.ui.screens.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.settings.AppSettings
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppSettings())
    val state = _state.asStateFlow()

    init {
        // `settings.settings` is the Flow; `settings.snapshot` is a one-shot read.
        // The screen needs the Flow, or a toggle would not show as changed until
        // it was reopened.
        viewModelScope.launch { settings.settings.collect { _state.value = it } }
    }

    fun setTheme(v: String) = viewModelScope.launch { settings.setSmsTheme(v) }
    fun setSounds(v: Boolean) = viewModelScope.launch { settings.setSmsSounds(v) }
    fun setDeliveryReports(v: Boolean) = viewModelScope.launch { settings.setSmsDeliveryReports(v) }
    fun setAutoRetry(v: Boolean) = viewModelScope.launch { settings.setSmsAutoRetry(v) }
    fun setBubbles(v: String) = viewModelScope.launch { settings.setSmsBubbles(v) }
    fun setSwipeAction(v: String) = viewModelScope.launch { settings.setSmsSwipeAction(v) }
    fun setLinkPreviews(v: Boolean) = viewModelScope.launch { settings.setSmsLinkPreviews(v) }
    fun setGroupByTime(v: Boolean) = viewModelScope.launch { settings.setSmsGroupByTime(v) }
    fun setSpamWarnings(v: Boolean) = viewModelScope.launch { settings.setSmsWarnOnSpam(v) }
    fun setLinkWarnings(v: Boolean) = viewModelScope.launch { settings.setSmsWarnOnLinks(v) }
    fun setOtpBanner(v: Boolean) = viewModelScope.launch { settings.setSmsShowOtpBanner(v) }
    fun setAutoReply(v: Boolean) = viewModelScope.launch { settings.setAutoReplyEnabled(v) }
}

/**
 * Messaging settings.
 *
 * Laid out the way the stock app lays them out, because that is a shape people
 * already know how to read. The contents are different in one place, and it is
 * the important one — see the rich-messaging card at the bottom.
 *
 * **Every switch on this screen changes something.** Where a setting cannot work
 * in this app, it is not offered as a dead toggle; it is explained. That rule is
 * the reason this screen is shorter than the one it is modelled on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesSettingsScreen(
    onBack: () -> Unit,
    onOpenSpamBlocked: () -> Unit = {},
) {
    // The theme wraps the whole screen, so the surface behind the content is
    // themed too rather than only what is drawn on it.
    MessagesTheme {
        MessagesSettingsScreenContent(onBack, onOpenSpamBlocked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessagesSettingsScreenContent(
    onBack: () -> Unit,
    onOpenSpamBlocked: () -> Unit = {},
    vm: MessagesSettingsViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
) { pad ->
    Column(
        Modifier
            .padding(pad)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------- general --
        SectionHeader("General")
        KavachCard {
            ChoiceRow(
                title = "Choose theme",
                options = listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                selected = s.smsTheme,
                onSelect = vm::setTheme,
            )
            Divider()
            SwitchRow(
                title = "Message sounds",
                subtitle = "A sound when a message is sent or received.",
                checked = s.smsSoundsEnabled,
                onChange = vm::setSounds,
            )
            Divider()
            SwitchRow(
                title = "Group by time",
                subtitle = "Today, Yesterday, Older — instead of one long list.",
                checked = s.smsGroupByTime,
                onChange = vm::setGroupByTime,
            )
            Divider()
            InfoRow(
                title = "Your country or region",
                value = "Detected from the SIM, used to read numbers correctly.",
            )
        }

        // ------------------------------------------------------ notifications --
        SectionHeader("Notifications")
        KavachCard {
            ChoiceRow(
                title = "Bubbles",
                options = listOf(
                    "all" to "All",
                    "selected" to "Selected",
                    "none" to "None",
                ),
                selected = s.smsBubbles,
                onSelect = vm::setBubbles,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (s.smsBubbles) {
                    "all" -> "Every conversation can float over other apps."
                    "selected" -> "Only conversations you mark can float over other apps."
                    else -> "Conversations stay in the notification shade."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------ sending --
        SectionHeader("Sending")
        KavachCard {
            SwitchRow(
                title = "Delivery reports",
                subtitle = "Ask the carrier to confirm each message reached the handset.",
                checked = s.smsDeliveryReports,
                onChange = vm::setDeliveryReports,
            )
            // Said plainly, because "read receipt" is what people expect from
            // the word and it is not what this is.
            Text(
                "A delivery report says the handset received it. It never says a " +
                    "person read it — nothing in SMS can, and a setting that claimed " +
                    "otherwise would be making it up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Divider()
            SwitchRow(
                title = "Retry automatically",
                subtitle = "Send again if the first attempt fails.",
                checked = s.smsAutoRetry,
                onChange = vm::setAutoRetry,
            )
            Divider()
            SwitchRow(
                title = "Auto-reply",
                subtitle = "Answer incoming messages with a set text, without asking.",
                checked = s.autoReplyEnabled,
                onChange = vm::setAutoReply,
            )
        }

        // ------------------------------------------------------------ safety --
        SectionHeader("Protection and safety")
        KavachCard {
            SwitchRow(
                title = "Warn about spam",
                subtitle = "Flag a conversation Kavach thinks is unwanted.",
                checked = s.smsWarnOnSpam,
                onChange = vm::setSpamWarnings,
            )
            Divider()
            SwitchRow(
                title = "Warn about links",
                subtitle = "Check a link before you tap it and say what it is.",
                checked = s.smsWarnOnLinks,
                onChange = vm::setLinkWarnings,
            )
            Divider()
            SwitchRow(
                title = "Show one-time-code banners",
                subtitle = "Pull the code out of a message so it is easy to copy.",
                checked = s.smsShowOtpBanner,
                onChange = vm::setOtpBanner,
            )
        }

        // ---------------------------------------------------------- media --
        SectionHeader("Attachments")
        KavachCard {
            // The stock app has auto-download settings here. Kavach does not
            // read MMS at all, so there is nothing to download and no switch
            // to offer — the same reasoning as the RCS card below.
            Text(
                "Kavach reads SMS, not MMS. Picture messages are not shown here, so " +
                    "there is nothing to auto-download and no setting for it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------- swipe actions --
        SectionHeader("Swipe actions")
        KavachCard {
            ChoiceRow(
                title = "Swiping a conversation",
                options = listOf(
                    "archive" to "Archive",
                    "delete" to "Bin",
                    "none" to "Nothing",
                ),
                selected = s.smsSwipeAction,
                onSelect = vm::setSwipeAction,
            )
        }

        // ------------------------------------------------------ rich messaging --
        SectionHeader("Rich messaging")
        KavachCard {
            // **This is the honest card.**
            //
            // The stock app has an "RCS chats" screen with read receipts,
            // typing indicators and automatic fallback to SMS. Kavach cannot
            // offer any of that, and the reason is not a missing feature.
            Text(
                "RCS is not available in Kavach, and cannot be.",
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "RCS needs carrier support and a Google-operated backend. Google " +
                    "Messages has it because Google runs both ends. A messaging app " +
                    "installed from outside cannot switch it on, and a screen of " +
                    "toggles that did nothing would be worse than this paragraph.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "What does work here:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                "Plain SMS and MMS, through your carrier",
                "Delivery reports, above — the only receipt SMS has",
                "Link checking before you tap",
                "One-time-code detection",
                "Auto-reply and suggested replies",
            ).forEach { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --------------------------------------------------------------- spam --
        SectionHeader("Blocked")
        KavachCard {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Spam and blocked", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Numbers Kavach blocks, and what it has filtered.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButtonish("Open") { onOpenSpamBlocked() }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(10.dp))
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            options.forEach { (value, label) ->
                androidx.compose.material3.FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.onSurface)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextButtonish(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
}
