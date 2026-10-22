package com.kavach.caller.ui.screens.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.kavach.caller.data.repo.ContactPhotoStore
import com.kavach.caller.ui.components.FavouriteStar
import com.kavach.caller.ui.screens.contacts.Avatar
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R

/**
 * Create / edit a contact (F2.6). Handles multiple numbers, company, job title,
 * notes and tags, and deletes with a confirmation.
 *
 * [initial] is null when creating a new contact.
 */
/** The only thing this screen needs from DI: somewhere to put a photo. */
@HiltViewModel
class ContactPhotoViewModel @Inject constructor(
    val store: ContactPhotoStore,
) : ViewModel()

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

    // Per-contact ringtone picker (F6.x).
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data
                ?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) as? Uri
            draft = draft.copy(ringtoneUri = uri?.toString())
        }
    }

    fun pickRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone for this contact")
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                draft.ringtoneUri?.let { Uri.parse(it) },
            )
        }
        runCatching { ringtoneLauncher.launch(intent) }
    }

    val photoVm: ContactPhotoViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    var showPhotoUrl by remember { mutableStateOf(false) }
    var photoMessage by remember { mutableStateOf<String?>(null) }
    var captureTarget by remember { mutableStateOf<android.net.Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = captureTarget
        captureTarget = null
        if (saved && uri != null) {
            scope.launch {
                // The camera wrote straight into our own directory, so the path
                // is already ours to keep.
                val path = photoVm.store.importFrom(uri)
                if (path != null) draft = draft.copy(photoUri = path)
                else photoMessage = "Could not save the photo."
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = photoVm.store.importFrom(uri)
                if (path != null) {
                    draft = draft.copy(photoUri = path)
                } else {
                    photoMessage = "Could not read that image."
                }
            }
        }
    }

    fun takePhoto() {
        val (_, uri) = photoVm.store.newCaptureTarget()
        captureTarget = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { photoMessage = "No camera app available." }
    }

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
            FavouriteStar(
                isFavourite = draft.isFavourite,
                onToggle = { draft = draft.copy(isFavourite = !draft.isFavourite) },
            )
            TextButton(onClick = { if (canSave) onSave(draft) }, enabled = canSave) {
                Text(stringResource(R.string.save))
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // ---- photo ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (draft.photoUri != null) {
                    Avatar(
                        ContactEntity(displayName = draft.name.ifBlank { "?" }, photoUri = draft.photoUri),
                        size = 72,
                    )
                } else {
                    MonogramAvatar(draft.name.ifBlank { "?" }, size = 72)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.photo),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { takePhoto() }) { Text(stringResource(R.string.camera)) }
                        OutlinedButton(
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.gallery)) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showPhotoUrl = true }) { Text(stringResource(R.string.from_url)) }
                        if (draft.photoUri != null) {
                            TextButton(onClick = {
                                val old = draft.photoUri
                                draft = draft.copy(photoUri = null)
                                scope.launch { photoVm.store.delete(old) }
                            }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
            photoMessage?.let { text ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.name)) },
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
                        label = { Text(stringResource(R.string.label)) },
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
                        label = { Text(stringResource(R.string.number)) },
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
                Text(stringResource(R.string.add_another_number))
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("Work")
            OutlinedTextField(
                value = draft.company,
                onValueChange = { draft = draft.copy(company = it) },
                label = { Text(stringResource(R.string.company)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.jobTitle,
                onValueChange = { draft = draft.copy(jobTitle = it) },
                label = { Text(stringResource(R.string.job_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("Notes")
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { draft = draft.copy(notes = it) },
                label = { Text(stringResource(R.string.notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("Tags")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text(stringResource(R.string.add_a_tag)) },
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
                ) { Text(stringResource(R.string.add)) }
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

            Spacer(Modifier.height(16.dp))
            FieldLabel("Ringtone")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (draft.ringtoneUri.isNullOrBlank()) "System default" else "Custom ringtone",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { pickRingtone() }) { Text(stringResource(R.string.choose)) }
                if (!draft.ringtoneUri.isNullOrBlank()) {
                    TextButton(onClick = { draft = draft.copy(ringtoneUri = null) }) {
                        Text(stringResource(R.string.clear))
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
                ) { Text(stringResource(R.string.delete_contact_2)) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_contact)) },
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
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
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
