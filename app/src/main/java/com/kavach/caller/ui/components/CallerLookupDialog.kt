package com.kavach.caller.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.kavach.caller.domain.model.LookupStrategy
import com.kavach.caller.domain.model.LookupTarget
import com.kavach.caller.util.LookupLauncher
import com.kavach.caller.util.PhoneUtils

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
                                        is LookupLauncher.Result.Opened -> onDismiss()
                                        is LookupLauncher.Result.Failed -> message = result.reason
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun iconFor(target: LookupTarget): ImageVector = when (target.strategy) {
    LookupStrategy.WHATSAPP -> Icons.Filled.Chat
    LookupStrategy.TELEGRAM -> Icons.AutoMirrored.Filled.Send
    LookupStrategy.EMAIL -> Icons.Filled.Email
    LookupStrategy.WEB_SEARCH -> Icons.Filled.Search
    LookupStrategy.TRUECALLER -> Icons.Filled.Public
    LookupStrategy.APP_OR_STORE -> Icons.Filled.Apps
}
