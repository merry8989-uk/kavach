package com.kavach.caller.ui.screens.blocklist

import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import com.kavach.caller.domain.BlockRuleCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton

@HiltViewModel
class BlocklistViewModel @Inject constructor(
    private val repo: BlockRepository,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val rules = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(pattern: String, type: RuleType, action: BlockAction) = viewModelScope.launch {
        repo.upsert(BlockRuleEntity(pattern = pattern, type = type, action = action, label = pattern))
    }

    /** The whole list as a JSON file the user can keep or move to another phone. */
    fun export(): String = BlockRuleCodec.export(rules.value)

    var message by mutableStateOf<String?>(null)
        private set

    /**
     * Read a block-rule file.
     *
     * **Says what it skipped and why.** A silent import that dropped half a file
     * is a user who thinks they are protected and is not.
     */
    fun import(text: String) = viewModelScope.launch {
        val result = BlockRuleCodec.import(text)
        result.rules.forEach { repo.upsert(it) }
        message = when {
            result.rules.isEmpty() && result.skipped.isEmpty() ->
                "That file had no rules in it."
            result.skipped.isEmpty() ->
                "Imported ${result.rules.size} ${if (result.rules.size == 1) "rule" else "rules"}."
            result.rules.isEmpty() ->
                "Nothing imported: ${result.skipped.joinToString("; ")}"
            else ->
                "Imported ${result.rules.size}, skipped ${result.skipped.size}: " +
                    result.skipped.joinToString("; ")
        }
    }

    fun dismissMessage() { message = null }

    fun showImportError() { message = "That file could not be read." }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun toggle(id: Long, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(id, enabled) }
}

@Composable
fun BlocklistScreen(
    onBack: () -> Unit = {},
    vm: BlocklistViewModel = hiltViewModel(),
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(stringResource(R.string.blocking_rules),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            // Export and import, side by side. A block list takes a while to
            // build and is the sort of thing people want on a new phone.
            // `context` has to be in scope before the launcher's lambda closes
            // over it.
            val context = LocalContext.current
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                    if (text == null) vm.showImportError() else vm.import(text)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                ) { Text("Import") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val body = vm.export()
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_TEXT, body)
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(intent, "Export block rules"),
                        )
                    },
                ) { Text("Export") }
            }

            vm.message?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

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
        title = { Text(stringResource(R.string.new_blocking_rule)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.pattern_number_prefix_range_country)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.match_type), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    RuleType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t },
                            label = { Text(t.name) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.action), style = MaterialTheme.typography.labelLarge)
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
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
