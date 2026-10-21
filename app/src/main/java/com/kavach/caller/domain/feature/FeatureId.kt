package com.kavach.caller.domain.feature

import android.Manifest
import android.app.role.RoleManager

/** How the Features screen groups modules. */
enum class FeatureCategory(val title: String) {
    PROTECTION("Protection"),
    CALLS("Calls"),
    MESSAGING("Messaging"),
    BACKGROUND("Background & data"),
}

/**
 * The central feature registry (F16 / Phase 12).
 *
 * Every optional module in Kavach declares, in one place:
 *  - a stable id and human name;
 *  - whether it is on by default;
 *  - whether it is essential (and therefore not switchable off);
 *  - the permissions and Android role it needs;
 *  - the other features it depends on.
 *
 * This is what makes settings honest: a toggle can only be on if the device can
 * actually support it, and the UI can say exactly why when it can't.
 */
enum class FeatureId(
    val title: String,
    val description: String,
    val category: FeatureCategory,
    val defaultEnabled: Boolean,
    val essential: Boolean = false,
    val requiresRole: String? = null,
    val requiresPermissions: List<String> = emptyList(),
    val dependsOn: List<FeatureId> = emptyList(),
) {
    // ---------------- Protection ----------------
    CALL_SCREENING(
        title = "Call screening & blocking",
        description = "Reject or silence calls that match your rules, before the phone rings.",
        category = FeatureCategory.PROTECTION,
        defaultEnabled = true,
        essential = true,
        requiresRole = RoleManager.ROLE_CALL_SCREENING,
        requiresPermissions = listOf(Manifest.permission.READ_PHONE_STATE),
    ),
    CALLER_ID(
        title = "Caller identification",
        description = "Show who is calling, using your contacts and the spam score.",
        category = FeatureCategory.PROTECTION,
        defaultEnabled = true,
        requiresPermissions = listOf(Manifest.permission.READ_CONTACTS),
    ),
    ANNOUNCER(
        title = "Caller announcer",
        description = "Speak the caller's name out loud when the phone rings.",
        category = FeatureCategory.PROTECTION,
        defaultEnabled = false,
    ),

    // ---------------- Calls ----------------
    CALL_RECORDING(
        title = "Call recording",
        description = "Record calls. Always announced to the caller.",
        category = FeatureCategory.CALLS,
        defaultEnabled = false,
        requiresPermissions = listOf(Manifest.permission.RECORD_AUDIO),
    ),
    MISSED_REMINDERS(
        title = "Missed-call reminders",
        description = "Notify you about calls you missed, with the caller's name.",
        category = FeatureCategory.CALLS,
        defaultEnabled = true,
        requiresPermissions = listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.POST_NOTIFICATIONS,
        ),
    ),
    AI_ASSISTANT(
        title = "AI call assistant",
        description = "Answer unknown calls, announce itself, capture the audio and send you a brief.",
        category = FeatureCategory.CALLS,
        defaultEnabled = true,
        requiresPermissions = listOf(Manifest.permission.RECORD_AUDIO),
        dependsOn = listOf(CALL_SCREENING),
    ),

    // ---------------- Messaging ----------------
    SMS_FEATURES(
        title = "Message features",
        description = "Read your threads, reply, and block senders.",
        category = FeatureCategory.MESSAGING,
        defaultEnabled = true,
        requiresPermissions = listOf(Manifest.permission.READ_SMS),
    ),

    // ---------------- Background ----------------
    BACKGROUND_SYNC(
        title = "Background sync",
        description = "Periodically import your call log so history stays complete.",
        category = FeatureCategory.BACKGROUND,
        defaultEnabled = true,
    ),
    UPDATE_CHECK(
        title = "Update checks",
        description = "Check GitHub for a newer Kavach build.",
        category = FeatureCategory.BACKGROUND,
        defaultEnabled = true,
        requiresPermissions = listOf(Manifest.permission.INTERNET),
    ),
}
