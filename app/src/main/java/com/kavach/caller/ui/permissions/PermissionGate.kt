package com.kavach.caller.ui.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kavach.caller.ui.components.KavachCard

/**
 * Ask for a permission at the moment it is needed, not before.
 *
 * The spec is explicit — *"Request permissions contextually, not all at first
 * launch"* — and it is right for a reason that has nothing to do with rules: a
 * dialog is only answerable when the person knows what it is for. "Allow Kavach
 * to access your contacts?" before the app has shown anything is a question with
 * no context, and the safe answer to a question like that is no.
 *
 * So each screen asks for what it needs, when it needs it, and says why in the
 * same breath. The first-run screen still offers to do everything at once, for
 * someone who would rather get it over with — but that is now a choice rather
 * than the only path.
 *
 * A card that appears **only** when the permission is missing.
 *
 * For screens that can render something useful without it — an empty contact
 * list still shows the app exists — this is the right shape: the screen stays,
 * and a card explains why it is empty and what would fill it. Rendering nothing
 * when the permission is held means it can sit at the top of a screen's column
 * without any restructuring.
 */
@Composable
fun PermissionNotice(
    permissions: List<String>,
    title: String,
    rationale: String,
    actionLabel: String = "Allow",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(permissions.all { context.isGranted(it) }) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { held = permissions.all { context.isGranted(it) } }

    LaunchedEffect(Unit) { held = permissions.all { context.isGranted(it) } }

    if (held) return

    Spacer(Modifier.height(8.dp))
    KavachCard {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            rationale,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    // Recorded with the same event the onboarding loop uses, so
                    // the two can be compared later: did asking at the point of
                    // use work better than asking everything up front? No new
                    // event type, so no schema change.
                    context.diagnosticsOrNull()?.let { repo ->
                        scope.launch {
                            repo.accessFixRequested(
                                permission = permissions.joinToString("+") { it.substringAfterLast('.') },
                                stateBefore = "denied",
                                attemptId = "ctx_" + UUID.randomUUID().toString().replace("-", "").take(8),
                            )
                        }
                    }
                    launcher.launch(permissions.toTypedArray())
                },
            ) { Text(actionLabel) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { context.openAppSettings() }) { Text("Open Settings") }
        }
    }
}

/**
 * Lets the gate record a request without every call site having to wire a
 * repository through. Same entry-point pattern the workers use.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface DiagnosticsEntryPoint {
    fun diagnostics(): com.kavach.caller.data.diagnostics.DiagnosticsRepository
}

private fun Context.diagnosticsOrNull(): com.kavach.caller.data.diagnostics.DiagnosticsRepository? =
    runCatching {
        dagger.hilt.android.EntryPointAccessors.fromApplication(this, DiagnosticsEntryPoint::class.java)
            .diagnostics()
    }.getOrNull()

internal fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

internal fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
