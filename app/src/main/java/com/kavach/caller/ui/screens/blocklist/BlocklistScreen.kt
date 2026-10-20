package com.kavach.caller.ui.screens.blocklist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.RuleType
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.ui.screens.contacts.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlocklistViewModel @Inject constructor(
    private val repo: BlockRepository,
) : ViewModel() {
    val rules = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(pattern: String, type: RuleType, action: BlockAction) = viewModelScope.launch {
        repo.upsert(BlockRuleEntity(pattern = pattern, type = type, action = action, label = pattern))
    }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun toggle(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(id, enabled) }
}

@Composable
fun BlocklistScreen(vm: BlocklistViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add rule")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                "Blocking rules",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (rules.isEmpty()) {
                EmptyState(
                    title = "No rules yet",
                    subtitle = "Block exact numbers, prefixes, ranges, country codes, or all private calls.",
                    action = "Add a rule",
                    onAction = { showAdd = true },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rules, key = { it.id }) { r ->
                        RuleRow(r, onToggle = { vm.toggle(r.id, it) }, onDelete = { vm.delete(r.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onAdd = { p, t, a -> vm.add(p, t, a); showAdd = false },
        )
    }
}

@Composable
private fun RuleRow(rule: BlockRuleEntity, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.label ?: rule.pattern, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
            Text("${rule.type} · ${rule.action}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (String, RuleType, BlockAction) -> Unit,
) {
    var pattern by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RuleType.EXACT) }
    var action by remember { mutableStateOf(BlockAction.BLOCK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New blocking rule") },
        text = {
            Column {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Pattern (number / prefix / range / country)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Match type", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    RuleType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t },
                            label = { Text(t.name) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Action", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    BlockAction.entries.forEach { a ->
                        FilterChip(selected = action == a, onClick = { action = a },
                            label = { Text(a.name) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pattern.isNotBlank()) onAdd(pattern, type, action) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
