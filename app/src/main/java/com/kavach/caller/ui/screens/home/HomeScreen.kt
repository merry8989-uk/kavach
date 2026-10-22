package com.kavach.caller.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.domain.feature.FeatureState
import com.kavach.caller.domain.feature.FeatureStatus
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/**
 * What Kavach is doing for you, in one screen.
 *
 * The spec lists a home dashboard as the first screen of Phase 1, and the
 * external review asked for the same thing: "a simple home screen with search
 * and protection status". Until now the app opened on a keypad, which shows what
 * the app *is* and nothing about what it is *doing*.
 *
 * Every number here is read from something real — the module registry, the call
 * log, the rules table. Nothing is estimated, and where a figure cannot be known
 * the card says so rather than showing a zero that looks like an answer.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val features: FeatureRegistry,
    private val callLog: com.kavach.caller.data.repo.CallLogRepository,
    private val blocks: com.kavach.caller.data.repo.BlockRepository,
) : ViewModel() {

    /** Today's activity, read from the call log. */
    data class Today(
        val calls: Int = 0,
        val missed: Int = 0,
        val flagged: Int = 0,
    )

    private val _states = MutableStateFlow<List<FeatureState>>(emptyList())
    val states: StateFlow<List<FeatureState>> = _states.asStateFlow()

    private val _today = MutableStateFlow(Today())
    val today: StateFlow<Today> = _today.asStateFlow()

    private val _ruleCount = MutableStateFlow(0)
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            callLog.observeAll().collect { entries -> _today.value = summarise(entries) }
        }
        viewModelScope.launch {
            blocks.observeAll().collect { rules -> _ruleCount.value = rules.count { it.enabled } }
        }
    }

    fun refresh() {
        // stateOf re-checks permissions and roles every time, so pulling to
        // refresh after granting something shows the change rather than a
        // stale answer.
        _states.value = features.allStates()
    }

    /**
     * What needs attention, worst first.
     *
     * A module that is off by choice is not a problem and is not listed — the
     * list is for things the user would want to know about, not a nag screen.
     */
    fun needsAttention(): List<FeatureState> = _states.value
        .filter { it.status == FeatureStatus.UNAVAILABLE }
        .filter { it.enabled }
        .sortedBy { it.id.ordinal }

    val enabledCount: Int get() = _states.value.count { it.status == FeatureStatus.ENABLED }
    val totalCount: Int get() = _states.value.size

    /** Is the thing that makes Kavach a call blocker actually running? */
    val protectionOn: Boolean
        get() = features.isEnabled(FeatureId.CALL_SCREENING) &&
            features.stateOf(FeatureId.CALL_SCREENING).status != FeatureStatus.UNAVAILABLE

    private fun summarise(entries: List<com.kavach.caller.data.local.entity.CallLogEntity>): Today {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val todays = entries.filter { it.timestamp >= startOfDay }
        return Today(
            calls = todays.size,
            // CallLog.Calls.MISSED_TYPE is 3; the entity stores the raw provider type.
            missed = todays.count { it.type == 3 },
            flagged = todays.count { it.spamScore >= 50 },
        )
    }
}

@Composable
fun HomeScreen(
    onOpenNumber: (String) -> Unit = {},
    onOpenRecordings: () -> Unit = {},
    onOpenBlocklist: () -> Unit = {},
    onOpenFeatures: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val states by vm.states.collectAsState()
    val today by vm.today.collectAsState()
    val rules by vm.ruleCount.collectAsState()

    var query by remember { mutableStateOf("") }
    val attention = remember(states) { vm.needsAttention() }

    val good = MaterialTheme.colorScheme.primary
    val warn = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                "Kavach",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Armour for every call",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }

        // ---------------------------------------------------- protection status
        item {
            Spacer(Modifier.height(14.dp))
            KavachCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            if (vm.protectionOn) good else warn,
                            CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (vm.protectionOn) "Protection is on" else "Protection is off",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (vm.protectionOn) {
                        "Incoming calls are checked against your rules before the phone rings."
                    } else {
                        "Calls are not being checked. Turn call screening on to block and " +
                            "silence the numbers you have ruled out."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${vm.enabledCount} of ${vm.totalCount} modules running · " +
                        "$rules blocking rule(s) active",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }

        // ------------------------------------------------------ what needs a look
        if (attention.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                SectionHeader("Needs a look")
            }
            items(attention.size) { index ->
                val state = attention[index]
                KavachCard {
                    Text(
                        state.id.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    // The registry's own words, which name the exact missing
                    // permission or role rather than a generic apology.
                    Text(
                        state.reason ?: "Unavailable on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            item {
                TextButton(onClick = onOpenFeatures) { Text("See all modules") }
            }
        }

        // ------------------------------------------------------------- today
        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader("Today")
            KavachCard {
                if (today.calls == 0) {
                    Text(
                        "No calls today.",
                        color = muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Kavach reads your call log, so this fills in as calls come and go.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Stat("Calls", today.calls.toString(), Modifier.weight(1f))
                        Stat("Missed", today.missed.toString(), Modifier.weight(1f))
                        Stat("Flagged", today.flagged.toString(), Modifier.weight(1f))
                    }
                    if (today.flagged > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Flagged means Kavach scored the call 50 or above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------ search
        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader("Look up a number")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Number") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { onOpenNumber(query.trim()) },
                enabled = query.count { it.isDigit() } >= 3,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open details") }
        }

        // --------------------------------------------------------- shortcuts
        item {
            Spacer(Modifier.height(6.dp))
            SectionHeader("Go to")
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                Shortcut("Blocklist", Icons.Filled.Shield, Modifier.weight(1f), onOpenBlocklist)
                Spacer(Modifier.width(10.dp))
                Shortcut("Recordings", Icons.Filled.Mic, Modifier.weight(1f), onOpenRecordings)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Shortcut("Call log", Icons.Filled.DateRange, Modifier.weight(1f)) {
                    onOpenNumber("")
                }
                Spacer(Modifier.width(10.dp))
                Shortcut("Modules", Icons.Filled.Call, Modifier.weight(1f), onOpenFeatures)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Shortcut(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
