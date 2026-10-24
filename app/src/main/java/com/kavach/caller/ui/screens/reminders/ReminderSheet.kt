package com.kavach.caller.ui.screens.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

/**
 * Setting a reminder about a person.
 *
 * The two destinations are shown as what they are, not as a settings toggle:
 *
 * - **In Kavach** — a notification from this app. Always available.
 * - **In the calendar** — written through `CalendarContract`, so if a Google
 *   account is synced it lands in Google Calendar and follows the account to
 *   other devices. Kavach calls no Google API and holds no Google credentials.
 *
 * The calendar option is offered only when it can actually work. Offering a
 * choice that silently does nothing is worse than not offering it — so when there
 * is no writable calendar the reason is shown instead of the switch.
 */
@Composable
fun ReminderSheet(
    number: String,
    contactName: String?,
    calendarAvailable: Boolean,
    calendarName: String?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, note: String?, dueAt: Long, toCalendar: Boolean) -> Unit,
) {
    var title by remember {
        mutableStateOf("Call ${contactName?.takeIf { it.isNotBlank() } ?: number}")
    }
    var note by remember { mutableStateOf("") }
    var toCalendar by remember { mutableStateOf(calendarAvailable) }
    var whenChoice by remember { mutableStateOf(WhenChoice.IN_AN_HOUR) }
    var customAt by remember { mutableStateOf(defaultCustom()) }

    val ready = title.isNotBlank() && (whenChoice != WhenChoice.PICK || customAt > System.currentTimeMillis())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What for") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("When", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    whenChoices.forEach { (choice, label) ->
                        FilterChip(
                            selected = whenChoice == choice,
                            onClick = { whenChoice = choice },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    if (toCalendar && calendarAvailable) {
                        "Goes into ${calendarName ?: "your calendar"}. If a Google account is synced " +
                            "it will appear in Google Calendar too, and follow that account to your " +
                            "other devices."
                    } else {
                        "Stays in Kavach. You get a notification here, and nothing is added to any " +
                            "calendar."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (calendarAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !toCalendar,
                            onClick = { toCalendar = false },
                            label = { Text("In Kavach") },
                        )
                        FilterChip(
                            selected = toCalendar,
                            onClick = { toCalendar = true },
                            label = { Text("In the calendar") },
                        )
                    }
                } else {
                    // Said rather than hidden: "why can I not pick the calendar"
                    // is a worse question than being told.
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No calendar on this device can be written to. Add an account in the " +
                            "Calendar app, or keep this reminder in Kavach.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), note.trim().ifBlank { null }, whenChoice.resolve(customAt), toCalendar) },
                enabled = ready && !submitting,
            ) { Text(if (submitting) "Saving…" else "Set reminder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class WhenChoice { IN_AN_HOUR, TONIGHT, TOMORROW_MORNING, NEXT_WEEK, PICK }

private val whenChoices = listOf(
    WhenChoice.IN_AN_HOUR to "In an hour",
    WhenChoice.TONIGHT to "Tonight",
    WhenChoice.TOMORROW_MORNING to "Tomorrow",
    WhenChoice.NEXT_WEEK to "Next week",
    WhenChoice.PICK to "Pick",
)

/**
 * Turn a choice into a time.
 *
 * Local wall-clock arithmetic, not a fixed millisecond offset: "tonight" means
 * the evening of the day the user is having, and adding a day's worth of
 * milliseconds to 11pm gives the following night.
 */
private fun WhenChoice.resolve(customAt: Long): Long {
    val cal = Calendar.getInstance()
    return when (this) {
        WhenChoice.IN_AN_HOUR -> System.currentTimeMillis() + 60 * 60 * 1000L
        WhenChoice.TONIGHT -> {
            cal.set(Calendar.HOUR_OF_DAY, 19)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            // If it is already past seven, tonight means tonight, not yesterday.
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.timeInMillis
        }
        WhenChoice.TOMORROW_MORNING -> {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.timeInMillis
        }
        WhenChoice.NEXT_WEEK -> {
            cal.add(Calendar.DAY_OF_YEAR, 7)
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.timeInMillis
        }
        WhenChoice.PICK -> customAt
    }
}

private fun defaultCustom(): Long {
    val cal = Calendar.getInstance()
    cal.add(Calendar.HOUR_OF_DAY, 1)
    return cal.timeInMillis
}
