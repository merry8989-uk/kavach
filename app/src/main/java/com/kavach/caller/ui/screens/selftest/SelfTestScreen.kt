package com.kavach.caller.ui.screens.selftest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.R
import com.kavach.caller.diagnostics.SelfTest
import com.kavach.caller.ui.components.KavachCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SelfTestViewModel @Inject constructor(
    private val selfTest: SelfTest,
) : ViewModel() {

    private val _report = MutableStateFlow<SelfTest.Report?>(null)
    val report: StateFlow<SelfTest.Report?> = _report.asStateFlow()

    var running by mutableStateOf(false)
        private set

    fun run() = viewModelScope.launch {
        running = true
        // Some checks touch the filesystem and the package manager, so they do
        // not belong on the main thread.
        _report.value = withContext(Dispatchers.IO) { selfTest.run() }
        running = false
    }

    fun summary(): String = _report.value?.let { selfTest.summarise(it) }.orEmpty()
}

/**
 * What this device can actually do (spec Phase 15).
 *
 * The spec is explicit: "Do not mark a feature VERIFIED based solely on
 * compilation." So this separates four things that are usually blurred together
 * — what passed, what failed, what could not run, and what only a real call or a
 * real message can settle.
 *
 * The last group is listed rather than hidden. A self-test that reported
 * "screening works" because the service is declared would be worse than none.
 */
@Composable
fun SelfTestScreen(
    onBack: () -> Unit = {},
    vm: SelfTestViewModel = hiltViewModel(),
) {
    val report by vm.report.collectAsState()

    LaunchedEffect(Unit) { if (report == null) vm.run() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Self-test",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.run() }, enabled = !vm.running) {
                Icon(Icons.Filled.Refresh, contentDescription = "Run again")
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                KavachCard {
                    Text(
                        "Runs on this device, now. Nothing here is inferred from a build " +
                            "succeeding.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (vm.running) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        report?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                vm.summary(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            report?.let { r ->
                section("Passed", r.passed, MaterialTheme.colorScheme.primary)
                section("Failed", r.failed, MaterialTheme.colorScheme.error)
                section("Not run", r.notRun, MaterialTheme.colorScheme.tertiary)
                section("Needs a device", r.needsDevice, MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * Not @Composable: this is a LazyListScope builder. The composables live inside
 * the `item { }` and `items { }` lambdas, which are composable contexts.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    checks: List<SelfTest.Check>,
    colour: Color,
) {
    if (checks.isEmpty()) return

    item {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(colour, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "$title · ${checks.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    items(checks, key = { it.name + title }) { check ->
        KavachCard {
            Text(
                check.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
