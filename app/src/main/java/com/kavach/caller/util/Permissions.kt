package com.kavach.caller.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Does this app currently hold [permission]?
 *
 * Lint flags every permission-gated API that is called without a visible check,
 * and it is right to. `runCatching` around the call catches the resulting
 * `SecurityException`, but it also swallows the reason — the feature silently
 * does nothing and nothing anywhere says why.
 *
 * An explicit check makes the decision legible: the app asks whether it may do
 * the thing, and if it may not, it declines for a reason it can name.
 */
fun Context.holds(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** True when every one of [permissions] is held. */
fun Context.holdsAll(vararg permissions: String): Boolean = permissions.all { holds(it) }
