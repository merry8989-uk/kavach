package com.kavach.caller.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.repo.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repo: ContactRepository,
) : ViewModel() {

    val contacts = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var query by mutableStateOf("")

    fun sync() = viewModelScope.launch { repo.syncFromSystem() }

    fun filtered(all: List<ContactEntity>): List<ContactEntity> {
        if (query.isBlank()) return all
        val q = query.lowercase()
        return all.filter {
            it.displayName.lowercase().contains(q) ||
                (it.company?.lowercase()?.contains(q) == true) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
        }
    }
}

@Composable
fun ContactsScreen(vm: ContactsViewModel = hiltViewModel()) {
    val all by vm.contacts.collectAsState()
    val list = vm.filtered(all)

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            label = { Text("Search name, company, tag") },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
        )

        if (all.isEmpty()) {
            EmptyState(
                title = "No contacts yet",
                subtitle = "Import your device contacts to see rich Kavach profiles.",
                action = "Sync contacts",
                onAction = { vm.sync() },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { c ->
                    ContactRow(c)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: ContactEntity) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(c.displayName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.displayName, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground)
            val sub = listOfNotNull(
                c.company?.takeIf { it.isNotBlank() },
                c.numbers.firstOrNull()?.number,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (c.tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row {
                    c.tags.take(3).forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag) },
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
        }
        if (c.numbers.size > 1) {
            Text("${c.numbers.size} nos", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun MonogramAvatar(name: String, size: Int = 44) {
    val palette = listOf(Color(0xFF38E1FF), Color(0xFF9A6BFF), Color(0xFFFF5CD2), Color(0xFFFFB020))
    val color = palette[(name.hashCode().let { if (it < 0) -it else it }) % palette.size]
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
