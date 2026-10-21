package com.kavach.caller.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kavach.caller.data.account.AccountRepository
import com.kavach.caller.domain.model.LookupStrategy
import com.kavach.caller.domain.model.LookupTarget
import com.kavach.caller.util.LookupLauncher
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Spam flagging and name suggestions, from the lookup sheet. */
@HiltViewModel
class ContributeViewModel @Inject constructor(
    private val account: AccountRepository,
) : ViewModel() {

    /** Only offered when a server is configured and the user is signed in. */
    val available: Boolean get() = account.isConfigured && account.isSignedIn

    fun report(number: String, category: String, onResult: (String) -> Unit) =
        viewModelScope.launch {
            account.reportSpam(number, category)
                .onSuccess(onResult)
                .onFailure { onResult(it.message ?: "Could not send the report.") }
        }

    fun suggest(number: String, name: String, onResult: (String) -> Unit) =
        viewModelScope.launch {
            account.contributeName(number, name)
                .onSuccess(onResult)
                .onFailure { onResult(it.message ?: "Could not send the suggestion.") }
        }
}

/**
 * "Who is this?" — the list of places a number can be looked up (F9.7).
 *
 * Used from the dialer, the call log and the contact detail screen.
 */
@Composable
fun CallerLookupDialog(
    number: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var suggestFor by remember { mutableStateOf<String?>(null) }
    val contribute: ContributeViewModel = hiltViewModel()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find the caller's name") },
        text = {
            Column {
                Text(
                    PhoneUtils.pretty(number),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Opens the number somewhere it might be identified. Kavach can't guarantee a name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                message?.let { text ->
                    Spacer(Modifier.height(10.dp))
                    Text(text, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(LookupTarget.entries, key = { it.name }) { target ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (val result = LookupLauncher.open(context, target, number)) {
                                        LookupLauncher.Result.Opened -> onDismiss()
                                        // Keep the sheet open so the note is actually read.
                                        is LookupLauncher.Result.OpenedWithNote ->
                                            message = result.message
                                        is LookupLauncher.Result.Failed ->
                                            message = result.reason
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                iconFor(target),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(target.title, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    target.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (contribute.available) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Help others",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Reports are weighted by how much your account is trusted. A name " +
                            "suggestion is held for review — only the number's owner can name it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { contribute.report(number, "fraud") { message = it } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Report spam") }
                        OutlinedButton(
                            onClick = { suggestFor = number },
                            modifier = Modifier.weight(1f),
                        ) { Text("Suggest name") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

    suggestFor?.let { target ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { suggestFor = null },
            title = { Text("Suggest a name") },
            text = {
                Column {
                    Text(
                        "This is held for review. It is never shown as somebody's name — only " +
                            "the number's owner or a verified business can name a number.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Suggested name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = name.trim()
                        suggestFor = null
                        if (value.isNotEmpty()) {
                            contribute.suggest(target, value) { message = it }
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { suggestFor = null }) { Text("Cancel") }
            },
        )
    }
}

private fun iconFor(target: LookupTarget): ImageVector = when (target.strategy) {
    LookupStrategy.WHATSAPP -> Icons.Filled.Chat
    LookupStrategy.TELEGRAM -> Icons.AutoMirrored.Filled.Send
    LookupStrategy.EMAIL -> Icons.Filled.Email
    LookupStrategy.WEB_SEARCH -> Icons.Filled.Search
    LookupStrategy.SITE_WITH_CLIPBOARD -> Icons.Filled.Public
    LookupStrategy.APP_OR_STORE -> Icons.Filled.Apps
    LookupStrategy.APP_THEN_SITE -> Icons.Filled.Apps
}
