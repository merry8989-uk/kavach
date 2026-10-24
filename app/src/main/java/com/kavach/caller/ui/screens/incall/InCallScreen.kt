package com.kavach.caller.ui.screens.incall

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kavach.caller.domain.model.CallDirection
import com.kavach.caller.domain.model.CallPhase
import com.kavach.caller.domain.model.CallSession
import com.kavach.caller.util.PhoneUtils
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.kavach.caller.R

private val AnswerGreen = Color(0xFF8FE39B)
private val AnswerGreenContent = Color(0xFF0B2B12)
private val HangupRed = Color(0xFFF2605C)
private val HangupRedContent = Color(0xFF3A0C0A)

private val DtmfKeys = listOf(
    "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#",
)

/**
 * The full-screen in-call experience (F1.5).
 *
 * Better than a stock dialer screen in three ways:
 *  - it shows the **contact name** when we know it, plus the region, so an
 *    unknown number is not just a wall of digits;
 *  - it shows a **live duration** and a clear **direction** marker;
 *  - the overflow menu holds working Hold / Record / Bluetooth controls.
 */
@Composable
fun InCallScreen(
    session: CallSession?,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleSpeaker: (Boolean) -> Unit,
    onDtmf: (Char) -> Unit,
    onDtmfStop: () -> Unit,
    onHold: (Boolean) -> Unit = {},
    onBluetooth: () -> Unit = {},
    onRecord: (String) -> Unit = {},
) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    var showKeypad by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    // The calling card, if this person has one. Loaded here rather than pushed
    // through the call session: the card is decoration, and a call must still
    // ring correctly if this lookup is slow or fails.
    val cardVm: com.kavach.caller.ui.screens.callingcard.CallingCardHolder = hiltViewModel()
    val card by cardVm.card.collectAsState()
    val number = session?.number.orEmpty()
    LaunchedEffect(number) { if (number.isNotBlank()) cardVm.load(number) }

    val connectedAt = session?.connectedAtMillis
    val connected = session?.isConnected == true
    LaunchedEffect(connectedAt, connected) {
        if (connectedAt == null || !connected) {
            elapsedSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000).coerceAtLeast(0)
            delay(1_000)
        }
    }

    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_active_call),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val ringing = session.phase == CallPhase.RINGING

    Box(Modifier.fillMaxSize()) {
        // The card's image, if there is one, drawn behind the whole screen so it
        // reads as a call from this person rather than a list with a photo on it.
        card?.backgroundUri?.let { uri ->
            coil.compose.AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (card?.dimBackground != false) {
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.58f),
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.70f),
                            ),
                        ),
                    ),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
        // ---------- identity ----------
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = card?.headline?.takeIf { it.isNotBlank() }
                    ?: if (session.simLabel != null) {
                        "${if (ringing) "Incoming call via" else "Calling via"} ${session.simLabel}"
                    } else {
                        session.phaseLabel
                    },
                color = if (card != null) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(14.dp))

            // Name first when we know it — the thing a stock dialer often omits.
            if (session.displayName != null) {
                Text(
                    session.displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (card != null) Color.White else MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
            }

            Text(
                text = if (session.number.isBlank()) "Private number" else PhoneUtils.pretty(session.number),
                style = if (session.displayName != null) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = if (session.displayName != null) FontWeight.Normal else FontWeight.SemiBold,
                color = if (session.displayName != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                textAlign = TextAlign.Center,
            )

            PhoneUtils.regionFor(session.number)?.let { region ->
                Spacer(Modifier.height(6.dp))
                Text(
                    region,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))

            // Direction + state + live duration, in one scannable row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val directionIcon = when (session.direction) {
                    CallDirection.INCOMING -> Icons.Filled.CallReceived
                    CallDirection.OUTGOING -> Icons.Filled.CallMade
                    CallDirection.UNKNOWN -> null
                }
                directionIcon?.let {
                    Icon(
                        it,
                        contentDescription = session.directionLabel,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = if (connected) {
                        "${session.directionLabel} · ${PhoneUtils.formatDuration(elapsedSeconds)}"
                    } else {
                        session.directionLabel
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(10.dp))
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (session.isKnownContact) "Saved contact" else "Not in your contacts",
                    )
                },
            )

            // ---- what we know about this caller, and why ----
            //
            // The review's first priority, and the second half is the important
            // one: a warning has to carry its reason. "Risk 78" tells someone
            // nothing they can act on — it does not say who says so or what they
            // claimed — and a score with no reason reads like evidence.
            session.verdict?.let { verdict ->
                Spacer(Modifier.height(14.dp))
                val accent = if (verdict.isWarning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (verdict.isWarning) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        verdict.headline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        verdict.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (verdict.isCommunityAllegation) {
                        Spacer(Modifier.height(5.dp))
                        // Said once more, plainly. A community report is not a
                        // finding, and the difference matters when the subject is
                        // a person's phone number.
                        Text(
                            "This is other people's reports, not a verified fact.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ---------- DTMF keypad ----------
        if (showKeypad && !ringing) {
            DtmfPad(
                onKey = { tone ->
                    onDtmf(tone)
                },
                onKeyRelease = { onDtmfStop() },
                onHide = { showKeypad = false },
            )
        }

        // ---------- controls ----------
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ControlTile(
                        icon = Icons.Filled.Dialpad,
                        label = "Keypad",
                        enabled = !ringing,
                        active = showKeypad,
                        onClick = { showKeypad = !showKeypad },
                    )
                    ControlTile(
                        icon = if (session.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = "Mute",
                        enabled = connected,
                        active = session.isMuted,
                        onClick = { onToggleMute(!session.isMuted) },
                    )
                    ControlTile(
                        icon = Icons.Filled.VolumeUp,
                        label = "Speaker",
                        enabled = connected,
                        active = session.isSpeakerOn,
                        onClick = { onToggleSpeaker(!session.isSpeakerOn) },
                    )
                    Box {
                        ControlTile(
                            icon = Icons.Filled.MoreVert,
                            label = "More",
                            enabled = true,
                            active = showMore,
                            onClick = { showMore = true },
                        )
                        DropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (session.phase == CallPhase.HOLDING) "Unhold" else "Hold") },
                                leadingIcon = { Icon(Icons.Filled.PauseCircle, contentDescription = null) },
                                enabled = connected,
                                onClick = {
                                    showMore = false
                                    onHold(session.phase != CallPhase.HOLDING)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.record_call)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                                },
                                onClick = {
                                    showMore = false
                                    onRecord(session.number)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bluetooth)) },
                                leadingIcon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
                                enabled = connected,
                                onClick = {
                                    showMore = false
                                    onBluetooth()
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                if (ringing) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        PillButton(
                            label = "Decline",
                            icon = Icons.Filled.CallEnd,
                            container = HangupRed,
                            content = HangupRedContent,
                            onClick = onHangup,
                        )
                        PillButton(
                            label = "Answer",
                            icon = Icons.Filled.Call,
                            container = AnswerGreen,
                            content = AnswerGreenContent,
                            onClick = onAnswer,
                        )
                    }
                } else {
                    PillButton(
                        label = "End call",
                        icon = Icons.Filled.CallEnd,
                        container = HangupRed,
                        content = HangupRedContent,
                        onClick = onHangup,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ControlTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        active -> MaterialTheme.colorScheme.primary
        enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val tint = when {
        active -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(container)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        modifier = Modifier.height(56.dp).widthIn(min = 160.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DtmfPad(
    onKey: (Char) -> Unit,
    onKeyRelease: () -> Unit,
    onHide: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DtmfKeys.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(key) {
                                detectTapGestures(onPress = {
                                    onKey(key.first())
                                    tryAwaitRelease()
                                    onKeyRelease()
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        TextButton(onClick = onHide) { Text(stringResource(R.string.hide_keypad)) }
    }
}
