package com.kavach.caller.ui.screens.contacts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.local.entity.PhoneNumber
import com.kavach.caller.data.repo.ContactDraft

/**
 * Create / edit a contact (F2.6). Handles multiple numbers, company, job title,
 * notes and tags, and deletes with a confirmation.
 *
 * [initial] is null when creating a new contact.
 */
@Composable
fun ContactEditScreen(
    initial: ContactEntity?,
    onCancel: () -> Unit,
    onSave: (ContactDraft) -> Unit,
    onDelete: (ContactEntity) -> Unit,
    /**
     * Pre-built draft, used when the editor is opened from the dialer (create a
     * contact from a typed number, or append the number to an existing one).
     */
    initialDraft: ContactDraft? = null,
) {
    var draft by remember(initial, initialDraft) {
        mutableStateOf(initialDraft ?: initial?.let { ContactDraft.from(it) } ?: ContactDraft())
    }
    var tagInput by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val canSave = draft.name.isNotBlank() || draft.numbers.any { it.number.isNotBlank() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (initial == null) "New contact" else "Edit contact",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = { if (canSave) onSave(draft) }, enabled = canSave) {
                Text("Save")
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("Phone numbers")
            draft.numbers.forEachIndexed { index, number ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = number.label,
                        onValueChange = { value ->
                            draft = draft.copy(
                                numbers = draft.numbers.toMutableList().also {
                                    it[index] = number.copy(label = value)
                                },
                            )
                        },
                        label = { Text("Label") },
                        modifier = Modifier.width(112.dp),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = number.number,
                        onValueChange = { value ->
                            draft = draft.copy(
                                numbers = draft.numbers.toMutableList().also {
                                    it[index] = number.copy(number = value)
                                },
                            )
                        },
                        label = { Text("Number") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    if (draft.numbers.size > 1) {
                        IconButton(
                            onClick = {
                                draft = draft.copy(
                                    numbers = draft.numbers.toMutableList().also { it.removeAt(index) },
                                )
                            },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove number")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(
                onClick = {
                    draft = draft.copy(numbers = draft.numbers + PhoneNumber("Mobile", ""))
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add another number")
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("Work")
            OutlinedTextField(
                value = draft.company,
                onValueChange = { draft = draft.copy(company = it) },
                label = { Text("Company") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.jobTitle,
                onValueChange = { draft = draft.copy(jobTitle = it) },
                label = { Text("Job title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("Notes")
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("Tags")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Add a tag") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val tag = tagInput.trim()
                        if (tag.isNotEmpty() && tag !in draft.tags) {
                            draft = draft.copy(tags = draft.tags + tag)
                        }
                        tagInput = ""
                    },
                ) { Text("Add") }
            }
            if (draft.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    draft.tags.forEach { tag ->
                        InputChip(
                            selected = false,
                            onClick = { draft = draft.copy(tags = draft.tags - tag) },
                            label = { Text(tag) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove tag",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }

            if (initial != null) {
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete contact") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete contact?") },
            text = {
                Text(
                    "This removes ${initial.displayName} from Kavach and, if it came from your " +
                        "phone's contacts, from the device too."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(initial)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
