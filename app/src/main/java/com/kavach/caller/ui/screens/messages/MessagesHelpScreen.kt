package com.kavach.caller.ui.screens.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader

/**
 * What messaging in Kavach does, and what it does not.
 *
 * Written as answers rather than features, because the questions people actually
 * have about a replacement messaging app are about trust and defaults: can it see
 * my messages, does it send anything, why is something missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesHelpScreen(
    onBack: () -> Unit,
) {
    // The theme wraps the whole screen, so the surface behind the content is
    // themed too rather than only what is drawn on it.
    MessagesTheme {
        MessagesHelpScreenContent(onBack)
    }
}

@Composable
private fun MessagesHelpScreenContent(onBack: () -> Unit) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Messages help") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            SectionHeader("Can Kavach read my messages?")
            KavachCard {
                Text(
                    "It reads them on this device to build the thread list, filter spam and " +
                        "find one-time codes. Nothing is uploaded, and nothing is sent to " +
                        "anyone — not to Kavach's servers, and not to a reputation service.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The only time a message leaves the phone is when you share or forward " +
                        "it yourself, using the system share sheet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Why is there no RCS?")
            KavachCard {
                Text(
                    "RCS needs carrier support and a Google-operated backend. Google " +
                        "Messages has it because Google runs both ends. An app installed " +
                        "from outside cannot switch it on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kavach sends SMS and MMS. Read receipts, typing indicators and " +
                        "automatic fallback between the two are RCS features and are not " +
                        "offered here, because they would not work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Does Kavach need to be my default SMS app?")
            KavachCard {
                Text(
                    "No — it can read and send without being the default. Two things do " +
                        "need the role, and Kavach says so where they appear:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "· Deleting a message from the device, rather than hiding it from Kavach.\n" +
                        "· Showing its own notification instead of the system's.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("What is the Bin?")
            KavachCard {
                Text(
                    "Putting a conversation in the Bin hides it from Kavach. It does not " +
                        "delete it — your carrier and the system messaging app still have " +
                        "it. Emptying the Bin clears Kavach's list and nothing else.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "That is deliberate. An app that cannot be your default SMS app has no " +
                        "business deleting messages from the device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Something is wrong")
            KavachCard {
                Text(
                    "Diagnostics → Email to the developer. It attaches the technical log " +
                        "and composes the message for you. Nothing is sent until you press " +
                        "send, and numbers in the log are masked before they are written.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}
