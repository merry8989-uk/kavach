package com.kavach.caller.ui.screens.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kavach.caller.data.local.entity.ReminderEntity
import com.kavach.caller.data.repo.ReminderRepository
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import androidx.compose.foundation.layout.width

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val repository: ReminderRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<ReminderEntity>>(emptyList())
    val items = _items.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch { _items.value = repository.pending() }

    fun markDone(id: Long) = viewModelScope.launch {
        repository.markDone(id)
        refresh()
    }

    /** Deleting also removes the calendar event it created — see the repository. */
    fun delete(id: Long) = viewModelScope.launch {
        repository.delete(id)
        refresh()
    }
}

/**
 * Reminders the user has set.
 *
 * Each row says where it went, because that is the part that is not visible from
 * here: a reminder in Kavach notifies from this app, and one in the calendar also
 * exists somewhere the user may look first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    vm: RemindersViewModel = hiltViewModel(),
) {
    val items by vm.items.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No reminders yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open a contact and tap Remind to set one. It can stay in Kavach, or go into " +
                        "your calendar — including Google Calendar, if that account is synced.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { reminder ->
                KavachCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (reminder.destination == ReminderEntity.DESTINATION_CALENDAR) {
                                Icons.Filled.CalendarMonth
                            } else {
                                Icons.Filled.NotificationsActive
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(reminder.title, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                buildString {
                                    append(formatWhen(reminder.dueAt))
                                    append(" · ")
                                    append(
                                        if (reminder.destination == ReminderEntity.DESTINATION_CALENDAR) {
                                            "in your calendar"
                                        } else {
                                            "in Kavach"
                                        },
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            reminder.contactKey?.let {
                                Text(
                                    PhoneUtils.pretty(it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            reminder.note?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton(onClick = { vm.delete(reminder.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete reminder")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.markDone(reminder.id) }) { Text("Done") }
                    }
                }
            }
        }
    }
}

/** "Today 7:00 pm", "Tomorrow 9:00 am", or a date. */
private fun formatWhen(at: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at))

    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today $time"

    val tomorrow = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = tomorrow.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        tomorrow.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    if (isTomorrow) return "Tomorrow $time"

    return SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(at))
}
