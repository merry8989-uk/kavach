package com.kavach.caller.ui.screens.onboarding

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private data class PermGroup(val title: String, val rationale: String, val permissions: List<String>)

private data class RoleItem(
    val title: String,
    val rationale: String,
    val role: String,
    val required: Boolean,
)

/** Permissions without which the app cannot do anything useful. */
private val corePermissions = listOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_PHONE_STATE,
)

fun hasCorePermissions(context: Context): Boolean = corePermissions.all { isGranted(context, it) }

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isRoleHeld(context: Context, role: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val manager = context.getSystemService(RoleManager::class.java) ?: return false
    return manager.isRoleAvailable(role) && manager.isRoleHeld(role)
}

private fun permissionGroups(): List<PermGroup> = buildList {
    add(
        PermGroup(
            "Contacts",
            "Identify callers and show rich profiles",
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        )
    )
    add(
        PermGroup(
            "Phone & call log",
            "Power the dialer, SIM picker and call history",
            buildList {
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.READ_CALL_LOG)
                add(Manifest.permission.WRITE_CALL_LOG)
                add(Manifest.permission.CALL_PHONE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    add(Manifest.permission.READ_PHONE_NUMBERS)
                    add(Manifest.permission.ANSWER_PHONE_CALLS)
                }
                // Only meaningful before Android 10; harmless to skip on newer.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    add(Manifest.permission.PROCESS_OUTGOING_CALLS)
                }
            },
        )
    )
    add(
        PermGroup(
            "In-call screen",
            "Keep the screen awake and show the call UI over the lock screen",
            buildList {
                add(Manifest.permission.WAKE_LOCK)
                add(Manifest.permission.DISABLE_KEYGUARD)
                add(Manifest.permission.VIBRATE)
            },
        )
    )
    add(
        PermGroup(
            "Messages",
            "Threads, smart replies and spam SMS filtering",
            listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
            ),
        )
    )
    add(
        PermGroup(
            "Microphone & call audio",
            "Call recording and the AI assistant",
            listOf(
                Manifest.permission.RECORD_AUDIO,
                // Needed to route audio to the right output while recording.
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
            ),
        )
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(
            PermGroup(
                "Notifications",
                "Call briefs, missed-call digest and update alerts",
                listOf(Manifest.permission.POST_NOTIFICATIONS),
            )
        )
    }
}

//
// Only roles Kavach can actually hold are listed. ROLE_SMS was removed: the
// app declares no SMS intent filters, so Android would never grant it, and
// offering a button that cannot work is the dishonest UI we avoid. Message
// features run from the READ_SMS permission instead.
private fun roleItems(): List<RoleItem> = listOf(
    RoleItem(
        "Default dialer",
        "Replaces the stock dialer — in-call screen, dual-SIM, recording",
        RoleManager.ROLE_DIALER,
        required = true,
    ),
    RoleItem(
        "Caller ID & spam",
        "Identifies and blocks callers before the phone rings",
        RoleManager.ROLE_CALL_SCREENING,
        required = true,
    ),
)

private fun allPermissions(): Array<String> =
    permissionGroups().flatMap { it.permissions }.toTypedArray()

/**
 * Access the OS grants from a system settings page rather than a dialog.
 * Kavach treats every one of these as optional and says so.
 */
private data class SpecialAccess(
    val title: String,
    val rationale: String,
    val granted: (Context) -> Boolean,
    val intent: (Context) -> Intent?,
)

private fun specialAccessItems(): List<SpecialAccess> = buildList {
    add(
        SpecialAccess(
            title = "Show over other apps (optional)",
            rationale = "Puts the caller's name on screen while the phone rings. Without it, " +
                "the name still appears in the call notification.",
            granted = { Settings.canDrawOverlays(it) },
            intent = { context ->
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(
            SpecialAccess(
                title = "Full-screen call alerts",
                rationale = "Android 14 and newer require this to show the in-call screen " +
                    "instead of a heads-up notification.",
                granted = { context ->
                    val manager = context.getSystemService(NotificationManager::class.java)
                    manager?.canUseFullScreenIntent() ?: false
                },
                intent = {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.fromParts("package", it.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        )
    }
}

/**
 * First-run setup. Asks for every permission Kavach needs plus the system roles
 * it depends on: **default dialer** and **caller ID / call screening** (the
 * "call identity" role), with the SMS role as optional.
 *
 * A live status list ticks as the user grants things, and the primary button
 * always performs the next outstanding step, so a non-technical user can just
 * keep tapping it.
 */
@Composable
fun PermissionsScreen(
    onFinished: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val groups = remember { permissionGroups() }
    val roles = remember { roleItems() }
    val specialAccess = remember { specialAccessItems() }

    var permStatus by remember { mutableStateOf(List(groups.size) { false }) }
    var roleStatus by remember { mutableStateOf(List(roles.size) { false }) }

    // The permission and role dialogs are owned by the OS and run outside our
    // process, so we poll while this screen is visible instead of relying on a
    // result callback that may never fire for role requests.
    LaunchedEffect(Unit) {
        while (true) {
            permStatus = groups.map { group -> group.permissions.all { isGranted(context, it) } }
            roleStatus = roles.map { isRoleHeld(context, it.role) }
            delay(800)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    fun requestRole(role: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = context.getSystemService(RoleManager::class.java) ?: return
        if (manager.isRoleAvailable(role) && !manager.isRoleHeld(role)) {
            roleLauncher.launch(manager.createRequestRoleIntent(role))
        }
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    val grantedGroups = permStatus.count { it }
    val heldRoles = roleStatus.count { it }
    val missingGroup = groups.indices.firstOrNull { !permStatus[it] }
    val missingRole = roles.indices.firstOrNull { roles[it].required && !roleStatus[it] }

    val primaryLabel: String
    val primaryAction: () -> Unit
    when {
        missingGroup != null -> {
            primaryLabel = "Grant all permissions"
            primaryAction = { permissionLauncher.launch(allPermissions()) }
        }
        missingRole != null -> {
            primaryLabel = "Set as ${roles[missingRole].title}"
            primaryAction = { requestRole(roles[missingRole].role) }
        }
        else -> {
            primaryLabel = "Finish setup"
            primaryAction = onFinished
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
            "Kavach works best as your dialer and caller-ID app. Grant the permissions below and set " +
                "the two roles, then you're ready. Everything can be changed later in Settings.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "$grantedGroups of ${groups.size} permission groups · $heldRoles of ${roles.size} roles set",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(12.dp))
        Button(onClick = primaryAction, modifier = Modifier.fillMaxWidth()) {
            Text(primaryLabel)
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Permissions")
        groups.forEachIndexed { index, group ->
            StatusRow(
                title = group.title,
                subtitle = group.rationale,
                ok = permStatus[index],
                actionLabel = "Grant",
                onAction = { permissionLauncher.launch(group.permissions.toTypedArray()) },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Roles")
        roles.forEachIndexed { index, role ->
            StatusRow(
                title = role.title,
                subtitle = role.rationale,
                ok = roleStatus[index],
                actionLabel = "Set",
                onAction = { requestRole(role.role) },
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Role requests need Android 10 or newer. On older versions, set Kavach as the default " +
                    "dialer from the system Settings app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Special access (optional)")
        specialAccess.forEach { item ->
            StatusRow(
                title = item.title,
                subtitle = item.rationale,
                ok = item.granted(context),
                actionLabel = "Open",
                onAction = { item.intent(context)?.let { runCatching { context.startActivity(it) } } },
            )
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { openAppSettings() }, modifier = Modifier.fillMaxWidth()) {
            Text("Open app settings")
        }
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun StatusRow(
    title: String,
    subtitle: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = if (ok) "Granted" else "Not granted",
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ok) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
