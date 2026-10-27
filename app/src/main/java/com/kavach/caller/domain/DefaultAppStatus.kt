package com.kavach.caller.domain

import android.app.role.RoleManager
import android.content.Context
import android.os.Build

/**
 * Whether Kavach is currently the default dialer and caller-ID app.
 *
 * One place, because Settings, the prompt and the worker all need the same
 * answer and three copies of it is how they disagree.
 *
 * The roles are checked individually rather than as "am I the default dialer",
 * because Kavach needs **both** and a user can grant one without the other. Being
 * the default dialer without call screening is a real state — the in-call screen
 * works and caller identification does not — and reporting it as "done" would
 * hide half a job.
 */
object DefaultAppStatus {

    /** The roles Kavach can actually hold, in the order setup asks for them. */
    val roles: List<Role> = listOf(
        Role(RoleManager.ROLE_DIALER, "Default dialer"),
        Role(RoleManager.ROLE_CALL_SCREENING, "Caller ID and spam"),
    )

    data class Role(val id: String, val label: String)

    /**
     * True when every role is held.
     *
     * On Android below Q there is no `RoleManager`; the roles are decided through
     * the default-apps settings and cannot be read this way. Reporting false is
     * the safe answer there — it offers the button rather than claiming a state
     * we cannot see.
     */
    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.all { role ->
            runCatching { manager.isRoleAvailable(role.id) && manager.isRoleHeld(role.id) }
                .getOrDefault(false)
        }
    }

    /** Which roles are still missing, for a row that says what is left. */
    fun missing(context: Context): List<Role> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return roles
        val manager = context.getSystemService(RoleManager::class.java) ?: return roles
        return roles.filter { role ->
            !runCatching { manager.isRoleAvailable(role.id) && manager.isRoleHeld(role.id) }
                .getOrDefault(false)
        }
    }

    /** The intent that asks the system for [role], or null when it cannot. */
    fun requestIntent(context: Context, role: String): android.content.Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = context.getSystemService(RoleManager::class.java) ?: return null
        return runCatching {
            if (manager.isRoleAvailable(role)) manager.createRequestRoleIntent(role) else null
        }.getOrNull()
    }
}
