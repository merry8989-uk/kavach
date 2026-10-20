package com.kavach.caller.ui.screens.onboarding

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Permissions that must be granted for the core features to function at all:
 * contacts (identity), call log (history), phone state (SIM + screening).
 */
private val corePermissions = listOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_PHONE_STATE,
)

fun hasCorePermissions(context: Context): Boolean = corePermissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun allRuntimePermissions(): Array<String> = buildList {
    addAll(corePermissions)
    add(Manifest.permission.WRITE_CONTACTS)
    add(Manifest.permission.WRITE_CALL_LOG)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_PHONE_NUMBERS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/**
 * First-run onboarding: runtime permissions + the system roles Kavach needs
 * (call screening, default dialer, default SMS). Without this screen the app
 * looked broken because nothing was ever granted.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onDone() }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    fun requestRole(role: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(role) && !rm.isRoleHeld(role)) {
                roleLauncher.launch(rm.createRequestRoleIntent(role))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text(
            "Set up Kavach",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Kavach needs a few permissions to identify callers, block spam and record calls. You can change any of these later.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { permissionLauncher.launch(corePermissions.toTypedArray()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant core permissions") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { permissionLauncher.launch(allRuntimePermissions()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant all permissions") }

        Spacer(Modifier.height(24.dp))
        Text("Roles", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        RoleRow("Call screening", "Block and identify calls before they ring") {
            requestRole(RoleManager.ROLE_CALL_SCREENING)
        }
        RoleRow("Default dialer", "Powers the built-in dialer and in-call screen") {
            requestRole(RoleManager.ROLE_DIALER)
        }
        RoleRow("Default SMS app", "Enables smart replies and spam SMS filtering") {
            requestRole(RoleManager.ROLE_SMS)
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoleRow(title: String, subtitle: String, onRequest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRequest) { Text("Enable") }
    }
}
