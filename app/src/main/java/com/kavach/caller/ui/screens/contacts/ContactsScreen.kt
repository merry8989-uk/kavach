package com.kavach.caller.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.repo.ContactDraft
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.usecase.SaveContactUseCase
import com.kavach.caller.domain.usecase.SyncContactsUseCase
import com.kavach.caller.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R
import com.kavach.caller.ui.permissions.PermissionNotice
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repo: ContactRepository,
    private val saveContact: SaveContactUseCase,
    private val syncContacts: SyncContactsUseCase,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    val contacts = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var query by mutableStateOf("")
    var favouritesOnly by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun dismissError() {
        errorMessage = null
    }

    fun sync() = viewModelScope.launch {
        errorMessage = null
        runCatching { syncContacts() }
            .onFailure { errorMessage = "Could not import contacts: ${it.message}" }
    }

    /**
     * Import contacts from a .vcf file the user picked.
     *
     * Goes through [saveContact] rather than writing rows directly, so an
     * imported number is validated by the same rule as one typed in. A file full
     * of malformed numbers should not be the way into the database.
     *
     * **Reports what it did.** "Imported 12, skipped 2" is useful; a silent
     * success on a file of 300 that produced 12 is not.
     */
    fun importVCard(uri: android.net.Uri) = viewModelScope.launch {
        errorMessage = null
        val outcome = runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: error("The file could not be opened.")

            val cards = com.kavach.caller.domain.VCardReader.parse(text)
            if (cards.isEmpty()) {
                errorMessage = "That file had no contacts Kavach could read."
                return@launch
            }

            var saved = 0
            var skipped = 0
            for (card in cards) {
                val draft = ContactDraft(
                    name = card.displayName,
                    numbers = card.numbers
                        .map { (number, label) -> PhoneNumber(label, number) }
                        .ifEmpty { listOf(PhoneNumber("Mobile", "")) },
                    company = card.company.orEmpty(),
                    jobTitle = card.jobTitle.orEmpty(),
                    notes = card.notes.orEmpty(),
                )
                when (saveContact(draft)) {
                    is SaveContactUseCase.Result.Saved -> saved += 1
                    else -> skipped += 1
                }
            }

            errorMessage = if (skipped == 0) {
                "Imported $saved ${if (saved == 1) "contact" else "contacts"}."
            } else {
                "Imported $saved, skipped $skipped that had no usable name or number."
            }
        }
        outcome.onFailure {
            errorMessage = "Could not read that file: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    fun save(draft: ContactDraft) = viewModelScope.launch {
        errorMessage = null
        when (val result = saveContact(draft)) {
            is SaveContactUseCase.Result.Saved -> Unit
            SaveContactUseCase.Result.NeedsNameOrNumber ->
                errorMessage = "Add a name or at least one number."
            is SaveContactUseCase.Result.InvalidNumber ->
                errorMessage = "\"${result.number}\" doesn't look like a valid number."
        }
    }

    fun delete(contact: ContactEntity) = viewModelScope.launch { repo.deleteContact(contact) }

    fun toggleFavourite(contact: ContactEntity) = viewModelScope.launch {
        repo.toggleFavourite(contact)
    }

    fun visible(all: List<ContactEntity>): List<ContactEntity> {
        val base = if (favouritesOnly) all.filter { it.isFavourite } else all
        if (query.isBlank()) return base
        val q = query.lowercase()
        return base.filter {
            it.displayName.lowercase().contains(q) ||
                (it.company?.lowercase()?.contains(q) == true) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
        }
    }
}

private sealed interface ContactScreenState {
    data object Browse : ContactScreenState
    data class Detail(val contact: ContactEntity) : ContactScreenState
    data class Edit(val contact: ContactEntity) : ContactScreenState
    data class New(val draft: ContactDraft?) : ContactScreenState
}

@Composable
fun ContactsScreen(
    /** Open the calling-card editor for a number. */
    onOpenCallingCard: (String, String?) -> Unit = { _, _ -> },
    onOpenReminders: () -> Unit = {},
    /**
     * Start on the create form.
     *
     * How "Add contact" on Home works: it navigates here with this set, so the
     * form opens rather than the list. A separate screen would have been a second
     * place to keep the same form working.
     */
    startCreating: Boolean = false,
    vm: ContactsViewModel = hiltViewModel(),
) {
    var state by remember(startCreating) {
        mutableStateOf<ContactScreenState>(
            if (startCreating) ContactScreenState.New(null) else ContactScreenState.Browse,
        )
    }
    val all by vm.contacts.collectAsState()

    // ---- detail ----
    val detail = state
    if (detail is ContactScreenState.Detail) {
        // Re-read from the list so a favourite toggle or edit shows immediately.
        val fresh = all.firstOrNull { it.id == detail.contact.id } ?: detail.contact
        ContactDetailScreen(
            contact = fresh,
            onBack = { state = ContactScreenState.Browse },
            onEdit = { state = ContactScreenState.Edit(fresh) },
            onDelete = { vm.delete(it); state = ContactScreenState.Browse },
            onToggleFavourite = { vm.toggleFavourite(it) },
            onOpenCallingCard = { number, name -> onOpenCallingCard(number, name) },
            onOpenReminders = onOpenReminders,
        )
        return
    }

    // ---- edit ----
    val edit = state
    if (edit is ContactScreenState.Edit) {
        ContactEditScreen(
            initial = edit.contact,
            onCancel = { state = ContactScreenState.Detail(edit.contact) },
            onSave = { vm.save(it); state = ContactScreenState.Browse },
            onDelete = { vm.delete(it); state = ContactScreenState.Browse },
        )
        return
    }

    // ---- create ----
    val create = state
    if (create is ContactScreenState.New) {
        ContactEditScreen(
            initial = null,
            initialDraft = create.draft,
            onCancel = { state = ContactScreenState.Browse },
            onSave = { vm.save(it); state = ContactScreenState.Browse },
            onDelete = { },
        )
        return
    }

    val list = vm.visible(all)
    val favourites = all.filter { it.isFavourite }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { state = ContactScreenState.New(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "Add contact")
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            PermissionNotice(
                permissions = listOf(android.Manifest.permission.READ_CONTACTS),
                title = "Kavach cannot see your contacts yet",
                rationale = "Reading contacts is what lets Kavach put a name to a number, " +
                    "and show company, tags and notes on the caller screen.",
            )
            vm.errorMessage?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { vm.dismissError() }) { Text(stringResource(R.string.dismiss)) }
                }
            }

            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                label = { Text(stringResource(R.string.search_name_company_tag)) },
                leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )

            // Import sits beside search rather than buried in a menu: a .vcf on
            // the phone is the only way to move contacts between apps, and a
            // feature nobody finds is the same as one that does not exist.
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri -> uri?.let { vm.importVCard(it) } }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        // Some file managers do not label .vcf, so the type list
                        // includes the generic one as a fallback.
                        importLauncher.launch(arrayOf("text/x-vcard", "text/vcard", "*/*"))
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Import .vcf")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (all.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !vm.favouritesOnly,
                        onClick = { vm.favouritesOnly = false },
                        label = { Text(stringResource(R.string.contacts_filter_all, all.size)) },
                    )
                    FilterChip(
                        selected = vm.favouritesOnly,
                        onClick = { vm.favouritesOnly = true },
                        label = { Text(stringResource(R.string.contacts_filter_favourites, favourites.size)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }

            if (all.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Group,
                    title = "No contacts yet",
                    subtitle = "Add one with the + button, or import your phone's contacts.",
                    action = "Import contacts",
                    onAction = { vm.sync() },
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (!vm.favouritesOnly && favourites.isNotEmpty() && vm.query.isBlank()) {
                        item {
                            SectionHeader("Favourites", Modifier.padding(start = 16.dp))
                            LazyRow(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(favourites, key = { it.id }) { c ->
                                    FavouriteBubble(c) { state = ContactScreenState.Detail(c) }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    item { SectionHeader("All contacts", Modifier.padding(start = 16.dp)) }

                    items(list, key = { it.id }) { c ->
                        ContactRow(c, onClick = { state = ContactScreenState.Detail(c) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        )
                    }

                    if (list.isEmpty()) {
                        item {
                            Text(
                                "Nothing matches \"${vm.query}\".",
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FavouriteBubble(contact: ContactEntity, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(contact, size = 54)
        Spacer(Modifier.height(6.dp))
        Text(
            contact.displayName.substringBefore(' '),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.displayName,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (contact.isFavourite) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favourite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            val sub = listOfNotNull(
                contact.company?.takeIf { it.isNotBlank() },
                contact.numbers.firstOrNull()?.number,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (contact.tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row {
                    contact.tags.take(3).forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        }
        if (contact.numbers.size > 1) {
            Text(
                "${contact.numbers.size} nos",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Photo via Coil when the device contact has one, else a coloured monogram. */
@Composable
fun Avatar(contact: ContactEntity, size: Int = 44) {
    if (contact.photoUri != null) {
        AsyncImage(
            model = contact.photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape),
        )
    } else {
        MonogramAvatar(contact.displayName, size)
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
fun EmptyState(
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
    icon: ImageVector? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(18.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
