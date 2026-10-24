package com.kavach.caller.ui.screens.callingcard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.kavach.caller.data.repo.CallingCardRepository
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import com.kavach.caller.data.local.entity.CallingCardEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The calling card editor.
 *
 * A card is how one person's call should look — a photo behind the name, a line
 * of your own above it. The preview at the top is the point of the screen: the
 * only way to choose a background for a phone call is to see it on a phone.
 *
 * Three ways in, and they are three different jobs rather than three routes to
 * the same picker:
 *
 * - **Camera** — take the picture now. The system camera app does the work.
 * - **Gallery** — the system photo picker, which is the device's own photos.
 * - **Google Photos** — the same picker, opened so its cloud sources are
 *   available. On Android 13 and up the picker lists Google Photos itself; on
 *   older versions it falls back to the document picker. Kavach calls no Google
 *   API and holds no Google credentials — it asks the system for a picture and
 *   the system decides where from.
 */
@HiltViewModel
class CallingCardViewModel @Inject constructor(
    private val repository: CallingCardRepository,
) : ViewModel() {

    var number by mutableStateOf("")
        private set
    var contactName by mutableStateOf<String?>(null)
        private set

    var headline by mutableStateOf("")
        private set
    var subtitle by mutableStateOf("")
        private set
    var imageUri by mutableStateOf<String?>(null)
        private set
    var dim by mutableStateOf(true)
        private set
    var enabled by mutableStateOf(true)
        private set
    var loading by mutableStateOf(true)
        private set
    var saved by mutableStateOf(false)
        private set
    var savedMessage by mutableStateOf<String?>(null)
        private set

    fun load(number: String, name: String?) {
        this.number = number
        contactName = name
        viewModelScope.launch {
            val card = repository.rawForNumber(number)
            headline = card?.headline.orEmpty()
            subtitle = card?.subtitle.orEmpty()
            imageUri = card?.backgroundUri
            dim = card?.dimBackground ?: true
            enabled = card?.enabled ?: true
            loading = false
        }
    }

    // These are `update*` rather than `set*` on purpose: `var headline by
    // mutableStateOf(...)` already generates `setHeadline`, and a function with
    // the same JVM signature is a compile error.
    fun setImage(uri: Uri?) { imageUri = uri?.toString(); saved = false }
    fun clearImage() { imageUri = null; saved = false }
    fun updateHeadline(value: String) { headline = value; saved = false }
    fun updateSubtitle(value: String) { subtitle = value; saved = false }
    fun updateDim(value: Boolean) { dim = value; saved = false }
    fun updateEnabled(value: Boolean) { enabled = value; saved = false }

    /** The headline shown when the user has not written one. */
    fun defaultHeadline(): String = "Incoming call"

    fun save() = viewModelScope.launch {
        val chosen = imageUri?.let { Uri.parse(it) }
        // The image is copied in only when it came from outside our own storage.
        val external = chosen?.takeIf { it.scheme == "content" }
        val ok = repository.save(
            number = number,
            pickedImage = external,
            clearImage = imageUri == null,
            headline = headline,
            subtitle = subtitle,
            dimBackground = dim,
            enabled = enabled,
        )
        saved = ok
        savedMessage = if (ok) {
            "Saved. This is how $number will look when it rings."
        } else {
            "Could not save the card. Nothing was changed."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallingCardScreen(
    number: String,
    contactName: String?,
    onBack: () -> Unit,
    vm: CallingCardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var loaded by remember(number) { mutableStateOf(false) }
    if (!loaded) { vm.load(number, contactName); loaded = true }

    // The camera writes into our own storage, so the system camera app needs a
    // URI it is allowed to write to. That is what the FileProvider path is for.
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) vm.setImage(pendingCapture) }

    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { vm.setImage(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calling card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.save() }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- the preview: a phone, because that is where it is seen ----
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(9f / 19.5f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .align(Alignment.CenterHorizontally),
            ) {
                vm.imageUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (vm.dim) {
                        // On by default: the failure mode of a photo background is
                        // white text on a bright sky and a name nobody can read.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Black.copy(alpha = 0.55f),
                                            Color.Black.copy(alpha = 0.20f),
                                            Color.Black.copy(alpha = 0.65f),
                                        ),
                                    ),
                                ),
                        )
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        vm.headline.ifBlank { vm.defaultHeadline() },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        contactName ?: PhoneUtils.pretty(number),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    if (vm.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            vm.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- the three ways in ----
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SourceButton(
                    label = "Camera",
                    icon = { Icon(Icons.Filled.AddAPhoto, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                ) {
                    val target = createCaptureUri(context)
                    if (target != null) {
                        pendingCapture = target
                        takePicture.launch(target)
                    }
                }
                SourceButton(
                    label = "Gallery",
                    icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                ) {
                    pickFromGallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
                SourceButton(
                    label = "Google Photos",
                    icon = { Icon(Icons.Filled.Collections, contentDescription = null) },
                    modifier = Modifier.weight(1f),
                ) {
                    // The same picker. It is the system's job to offer the cloud
                    // sources, and on 13+ Google Photos is one of them.
                    pickFromGallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            }

            if (vm.imageUri != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { vm.clearImage() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Remove the image")
                }
            }

            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = vm.headline,
                onValueChange = vm::updateHeadline,
                label = { Text("Line above the name") },
                placeholder = { Text(vm.defaultHeadline()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = vm.subtitle,
                onValueChange = vm::updateSubtitle,
                label = { Text("Line under the name") },
                placeholder = { Text("Anything you want to see when they call") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            ToggleRow(
                title = "Darken the image",
                subtitle = "Keeps the name readable over a bright photo.",
                checked = vm.dim,
                onChange = vm::updateDim,
            )
            ToggleRow(
                title = "Use this card",
                subtitle = "Off keeps it saved but shows the normal call screen.",
                checked = vm.enabled,
                onChange = vm::updateEnabled,
            )

            vm.savedMessage?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.save() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save card") }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SourceButton(
    label: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A file for the camera to write into, and a URI it is allowed to write to.
 *
 * The camera app runs in another process, so it cannot be handed a plain file
 * path — it needs a content URI with a temporary grant, which is what the
 * FileProvider path in `file_paths.xml` exists for.
 */
private fun createCaptureUri(context: android.content.Context): Uri? = runCatching {
    val dir = java.io.File(context.filesDir, "calling-cards").apply { mkdirs() }
    val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.jpg")
    androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}.getOrNull()

/**
 * Reads a calling card for the in-call screen.
 *
 * Separate from [CallingCardViewModel], which owns an editor's worth of state.
 * The call screen needs one value and must not fail loudly if it cannot get it:
 * a call has to ring correctly whether or not a card exists, so every path here
 * degrades to "no card".
 */
@HiltViewModel
class CallingCardHolder @Inject constructor(
    private val repository: CallingCardRepository,
) : ViewModel() {

    private val _card = MutableStateFlow<CallingCardEntity?>(null)
    val card = _card.asStateFlow()

    fun load(number: String) = viewModelScope.launch {
        _card.value = runCatching { repository.forNumber(number) }.getOrNull()
    }
}
