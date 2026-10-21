package com.kavach.caller.ui.screens.features

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.domain.feature.FeatureCategory
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.domain.feature.FeatureState
import com.kavach.caller.domain.feature.FeatureStatus
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader
import com.kavach.caller.work.WorkerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeaturesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FeatureRegistry,
) : ViewModel() {

    private val _states = MutableStateFlow<List<FeatureState>>(emptyList())
    val states: StateFlow<List<FeatureState>> = _states.asStateFlow()

    var message by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        _states.value = registry.allStates()
    }

    fun toggle(id: FeatureId, enabled: Boolean) = viewModelScope.launch {
        val applied = registry.setEnabled(id, enabled)
        if (!applied) {
            message = registry.enableBlockReason(id) ?: "That change isn't possible on this device."
        } else {
            message = null
            // Turning background sync off must actually stop the scheduled work.
            if (id == FeatureId.BACKGROUND_SYNC) {
                WorkerScheduler.schedule(context, backgroundSyncEnabled = enabled)
            }
        }
        refresh()
    }

    fun dismissMessage() {
        message = null
    }
}

/**
 * The Features screen (F16 / Phase 12).
 *
 * Every optional module, its real status, and — when it can't run — exactly why.
 * Modules that are unavailable are not silently switchable on.
 */
@Composable
fun FeaturesScreen(
    onBack: () -> Unit,
    vm: FeaturesViewModel = hiltViewModel(),
) {
    val states by vm.states.collectAsState()

    // Permissions and roles can change while this screen is open (the user may
    // go to system settings), so keep the statuses live.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(2_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Features",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        vm.message?.let { text ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { vm.dismissMessage() }) { Text("OK") }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            FeatureCategory.entries.forEach { category ->
                val inCategory = states.filter { it.id.category == category }
                if (inCategory.isNotEmpty()) {
                    SectionHeader(category.title)
                    inCategory.forEach { state ->
                        FeatureCard(state) { checked -> vm.toggle(state.id, checked) }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FeatureCard(state: FeatureState, onToggle: (Boolean) -> Unit) {
    KavachCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.id.title,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.id.essential) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Always on",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    state.id.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                StatusChip(state.status)
                state.reason?.let { reason ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = onToggle,
                enabled = !state.id.essential && state.status != FeatureStatus.UNAVAILABLE,
            )
        }
    }
}

@Composable
private fun StatusChip(status: FeatureStatus) {
    val (label, colour) = when (status) {
        FeatureStatus.ENABLED -> "On" to MaterialTheme.colorScheme.primary
        FeatureStatus.DEGRADED -> "Limited" to MaterialTheme.colorScheme.tertiary
        FeatureStatus.DISABLED -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
        FeatureStatus.UNAVAILABLE -> "Unavailable" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = colour.copy(alpha = 0.16f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colour,
        )
    }
}
