package com.kavach.caller.ui.screens.dialer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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
}

private val keys = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

@Composable
fun DialerScreen(vm: DialerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var previewName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(input) { previewName = vm.nameFor(input) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // Caller-ID preview card (F1.8)
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
        Spacer(Modifier.height(24.dp))

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
            // Place call (F1.3) — ACTION_CALL when CALL_PHONE granted, else ACTION_DIAL
            FloatingActionButton(
                onClick = {
                    if (input.isBlank()) return@FloatingActionButton
                    val uri = Uri.parse("tel:${PhoneUtils.toE164(input)}")
                    val intent = Intent(Intent.ACTION_DIAL, uri)
                    context.startActivity(intent)
                },
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
