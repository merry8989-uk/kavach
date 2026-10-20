package com.kavach.caller.ui.screens.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DialerViewModel @Inject constructor(
    private val contacts: ContactRepository,
) : ViewModel() {
    /** Resolve a name for the number being typed (caller-ID preview, F1.8). */
    suspend fun nameFor(number: String): String? =
        if (number.length >= 4) contacts.nameFor(number) else null

    /** T9 name search (F1.7): digits -> matching contacts. */
    suspend fun searchByT9(digits: String): List<ContactEntity> = contacts.searchByT9(digits)
}

private val keys = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

private data class SimOption(val label: String, val handle: PhoneAccountHandle?)

/** Active SIMs, or empty if we can't read them (no permission / single SIM). */
private fun activeSims(context: Context): List<SimOption> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED
    ) return emptyList()
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
    return runCatching {
        val subs = context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList ?: emptyList()
        telecom.callCapablePhoneAccounts.map { handle ->
            val info = subs.firstOrNull { it.subscriptionId.toString() == handle.id }
            SimOption(
                label = info?.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: handle.label?.toString()
                    ?: "SIM",
                handle = handle,
            )
        }
    }.getOrDefault(emptyList())
}

@Composable
fun DialerScreen(vm: DialerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var previewName by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    val sims = remember { activeSims(context) }
    var selectedSim by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(input) {
        previewName = vm.nameFor(input)
        matches = if (input.isNotEmpty() && input.all { it.isDigit() }) {
            vm.searchByT9(input)
        } else emptyList()
    }

    fun placeCall(raw: String) {
        if (raw.isBlank()) return
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val uri = Uri.parse("tel:${PhoneUtils.toE164(raw)}")
        // ACTION_CALL when we may place calls directly, otherwise hand off to
        // the system dialer (ACTION_DIAL) which always works.
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        if (canCall && sims.size > 1 && selectedSim in sims.indices) {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sims[selectedSim].handle)
        }
        runCatching { context.startActivity(intent) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        if (previewName != null) {
            AssistChip(onClick = {}, label = { Text(previewName!!) })
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = if (input.isEmpty()) "" else PhoneUtils.pretty(input),
            fontSize = 34.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )

        // T9 matches (F1.7)
        if (matches.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(matches, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.displayName) },
                        supportingContent = { Text(c.numbers.firstOrNull()?.number ?: "") },
                        modifier = Modifier.clickable {
                            c.numbers.firstOrNull()?.number?.let { n ->
                                input = PhoneUtils.normalize(n).trimStart('+')
                            }
                        },
                    )
                }
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }

        // Dual-SIM selector (F1.4)
        if (sims.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                sims.forEachIndexed { index, sim ->
                    FilterChip(
                        selected = selectedSim == index,
                        onClick = { selectedSim = index },
                        label = { Text(sim.label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Keypad (F1.1)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            keys.chunked(3).forEach { row ->
                Row {
                    row.forEach { (digit, letters) ->
                        KeypadButton(digit, letters) {
                            input += if (digit == "0" && input.isEmpty()) "+" else digit
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(72.dp))
            FloatingActionButton(
                onClick = { placeCall(input) },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Call, contentDescription = "Call")
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = { if (input.isNotEmpty()) input = input.dropLast(1) }) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun KeypadButton(digit: String, letters: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(6.dp)
            .size(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(digit, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                if (letters.isNotEmpty()) {
                    Text(letters, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
