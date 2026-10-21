package com.kavach.caller.ui.screens.policy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kavach.caller.ui.components.KavachCard
import com.kavach.caller.ui.components.SectionHeader

/**
 * How Kavach identifies callers, and the promises behind it.
 *
 * The full architecture document (docs/CALLER-ID-PLAN.md) ships inside the app
 * bundle, so the reasoning is readable on the device and not only in the repo.
 */
@Composable
fun DataPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var fullText by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        fullText = runCatching {
            context.assets.open(FULL_DOCUMENT).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "How Kavach identifies callers",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader("The three lawful sources of a name")
            KavachCard {
                Source("Self-declared", "The number's owner verified their number and chose to publish their own name.")
                Spacer(Modifier.height(10.dp))
                Source("Verified business", "A number published by the business itself.")
                Spacer(Modifier.height(10.dp))
                Source("Community opinion", "\"Spam\", \"Telemarketer\", \"Fraud\" — labels about a number, never a claim about a person.")
            }

            SectionHeader("What Kavach will not do")
            KavachCard {
                Never("Upload your phone book. Kavach never sends your contacts anywhere.")
                Never("Show a name from a community suggestion. Those are held for review, never published.")
                Never("Scrape a third-party caller-ID site.")
                Never("Store your number in the clear. The server keeps an HMAC-SHA256 hash.")
                Never("Sell data or share it with advertisers.")
            }

            SectionHeader("Your control")
            KavachCard {
                Right("See", "your profile and the full history of what you agreed to.")
                Right("Correct", "your display name whenever you like.")
                Right("Withdraw", "your name — it stops being shown immediately.")
                Right("Delete", "your account. Your name is withdrawn and your profile removed.")
            }

            SectionHeader("How a spam score is reached")
            KavachCard {
                Text(
                    "Reports are weighted by how much the reporter is trusted, decay over about " +
                        "90 days, and count for more when they come from many different people " +
                        "rather than one person repeating themselves.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "0–39 clean · 40–69 likely spam · 70–100 spam",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A spam label is a community opinion, not a finding of fact. A verified " +
                        "business or a number whose owner has published a name is discounted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionHeader("The full document")
            KavachCard {
                Text(
                    if (fullText == null) {
                        "The full architecture plan is not bundled with this build."
                    } else {
                        "The complete architecture, MVP plan and DPDP notes are bundled with " +
                            "this app — ${fullText!!.length} characters, readable offline."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    enabled = fullText != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (expanded) "Hide the document" else "Read the document")
                }
            }

            if (expanded && fullText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    fullText!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "This is engineering guidance, not legal advice. Have the privacy policy, consent " +
                    "flows and DPDP obligations reviewed by an Indian technology lawyer before " +
                    "launching publicly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Source(title: String, body: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Never(body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("✕", color = MaterialTheme.colorScheme.error, modifier = Modifier.width(22.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Right(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val FULL_DOCUMENT = "caller-id-plan.md"
