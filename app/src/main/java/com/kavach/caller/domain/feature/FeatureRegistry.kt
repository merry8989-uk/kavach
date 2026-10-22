package com.kavach.caller.domain.feature

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kavach.caller.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.kavach.caller.data.secure.SecretStore

/**
 * Single source of truth for "can this module run, and is it switched on?".
 *
 * Background components (services, workers) consult [isEnabled] so a disabled
 * module genuinely stops doing work rather than merely being hidden from the UI.
 */
@Singleton
class FeatureRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** User overrides, keyed by feature name. Kept warm for synchronous reads. */
    @Volatile
    private var overrides: Map<String, Boolean> = emptyMap()

    init {
        scope.launch { settings.featureOverrides.collect { overrides = it } }
    }

    /** Synchronous, safe to call from services and workers. */
    fun isEnabled(id: FeatureId): Boolean = overrides[id.name] ?: id.defaultEnabled

    fun stateOf(id: FeatureId): FeatureState {
        val missingPermissions = id.requiresPermissions.filter { !isGranted(it) }
        val missingRole = id.requiresRole?.takeIf { !isRoleHeld(it) }
        val missingDependencies = id.dependsOn.filter { !isEnabled(it) }
        val missingBackend = id.requiresBackend && settings.snapshot.backendBaseUrl.isBlank()
        val enabled = isEnabled(id)

        if (missingBackend) {
            return FeatureState(
                id = id,
                enabled = enabled,
                status = FeatureStatus.UNAVAILABLE,
                reason = "No Kavach server is configured. Add the server URL in Settings, " +
                    "or self-host the backend in this repository.",
            )
        }

        if (missingPermissions.isNotEmpty() || missingRole != null || missingDependencies.isNotEmpty()) {
            return FeatureState(
                id = id,
                enabled = enabled,
                status = FeatureStatus.UNAVAILABLE,
                reason = unavailableReason(missingPermissions, missingRole, missingDependencies),
                missingPermissions = missingPermissions,
                missingRole = missingRole,
                missingDependencies = missingDependencies,
            )
        }

        if (!enabled) {
            return FeatureState(
                id = id,
                enabled = false,
                status = FeatureStatus.DISABLED,
                reason = "Turned off.",
            )
        }

        // Enabled and supported — but some modules run with less than full power.
        degradedReason(id)?.let { reason ->
            return FeatureState(id, enabled = true, status = FeatureStatus.DEGRADED, reason = reason)
        }

        return FeatureState(id, enabled = true, status = FeatureStatus.ENABLED)
    }

    fun allStates(): List<FeatureState> = FeatureId.entries.map { stateOf(it) }

    /**
     * Why a module cannot be switched on, or null if it can.
     * Used to block the toggle and to explain the block in the UI.
     */
    fun enableBlockReason(id: FeatureId): String? {
        if (id.requiresBackend && settings.snapshot.backendBaseUrl.isBlank()) {
            return "Needs a Kavach server URL in Settings"
        }
        val missingPermissions = id.requiresPermissions.filter { !isGranted(it) }
        if (missingPermissions.isNotEmpty()) {
            return "Needs permission: ${missingPermissions.joinToString { it.substringAfterLast('.') }}"
        }
        id.requiresRole?.takeIf { !isRoleHeld(it) }?.let { role ->
            return "Needs the ${roleLabel(role)} role"
        }
        id.dependsOn.firstOrNull { !isEnabled(it) }?.let { dependency ->
            return "Requires \"${dependency.title}\" to be on first"
        }
        if (id.essential) {
            return "This is core protection and cannot be turned off"
        }
        return null
    }

    /**
     * Turn a module on or off. Returns false (and changes nothing) when the
     * request is not valid on this device.
     */
    suspend fun setEnabled(id: FeatureId, enabled: Boolean): Boolean {
        if (enabled && enableBlockReason(id) != null) return false
        if (!enabled && id.essential) return false
        settings.setFeatureOverride(id.name, enabled)
        return true
    }

    // ---------------- internals ----------------

    private fun degradedReason(id: FeatureId): String? = when (id) {
        FeatureId.REMOTE_LOOKUP ->
            if (!secrets.has(SecretStore.SERVER_TOKEN)) {
                "No API token set — the server will reject lookups."
            } else null

        FeatureId.AI_ASSISTANT ->
            if (!settings.snapshot.cloudAiEnabled) {
                "Running on-device only — enable cloud AI in Settings for full transcription and summaries."
            } else null

        FeatureId.CALLER_ID ->
            if (!isGranted(android.Manifest.permission.READ_PHONE_STATE)) {
                "Numbers may not resolve fully without phone-state access."
            } else null

        else -> null
    }

    private fun unavailableReason(
        missingPermissions: List<String>,
        missingRole: String?,
        missingDependencies: List<FeatureId>,
    ): String = buildString {
        if (missingRole != null) {
            append("Kavach does not hold the ${roleLabel(missingRole)} role")
        }
        if (missingPermissions.isNotEmpty()) {
            if (isNotEmpty()) append(". ")
            append("Missing permission: ")
            append(missingPermissions.joinToString { it.substringAfterLast('.') })
        }
        if (missingDependencies.isNotEmpty()) {
            if (isNotEmpty()) append(". ")
            append("Requires ")
            append(missingDependencies.joinToString { "\"${it.title}\"" })
        }
        append(".")
    }

    private fun roleLabel(role: String): String = when (role) {
        RoleManager.ROLE_CALL_SCREENING -> "caller ID & spam"
        RoleManager.ROLE_DIALER -> "default dialer"
        RoleManager.ROLE_SMS -> "SMS"
        else -> role
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isRoleHeld(role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { manager.isRoleAvailable(role) && manager.isRoleHeld(role) }
            .getOrDefault(false)
    }
}
