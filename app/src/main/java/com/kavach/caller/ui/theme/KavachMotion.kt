package com.kavach.caller.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How much motion this app is allowed to use, resolved once.
 *
 * Android already has an answer to this question. Turning animations off in
 * Accessibility sets `ANIMATOR_DURATION_SCALE` to zero, and every app is expected
 * to notice. The people who do that are not being fussy — reduced motion is what
 * makes a phone usable for someone with a vestibular disorder, and an app that
 * animates anyway is an app that makes them ill.
 *
 * So the default is **system**: Kavach follows Android unless the user says
 * otherwise. Two overrides exist because both are real needs — "full" for someone
 * who wants the motion the theme packs were designed around, and "reduced" for
 * someone whose device setting is fine but who does not want this app moving.
 *
 * A setting that controls nothing is a lie, so this is read by the navigation
 * transitions in `KavachNav` and by the screen-level animations. If nothing
 * animated, there would be nothing for it to control and it should not exist.
 */
enum class MotionMode {
    /** Follow Android's animator duration scale. */
    SYSTEM,
    /** Animate regardless of the system setting. */
    FULL,
    /** Never animate, regardless of the system setting. */
    REDUCED,
    ;

    companion object {
        val VALUES = setOf("system", "full", "reduced")

        fun from(value: String?): MotionMode = when (value?.lowercase()) {
            "full" -> FULL
            "reduced" -> REDUCED
            else -> SYSTEM
        }
    }
}

/**
 * True when the OS has animations turned off.
 *
 * `ANIMATOR_DURATION_SCALE` is the one that governs Compose transitions;
 * `TRANSITION_ANIMATION_SCALE` covers the older window transitions. Either being
 * zero means the user asked for no motion, and either is enough to honour.
 */
fun Context.systemAnimationsDisabled(): Boolean {
    fun scale(name: String): Float = runCatching {
        Settings.Global.getFloat(contentResolver, name, 1f)
    }.getOrDefault(1f)

    return scale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
        scale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f
}

/** Resolve the mode the app should actually use, given the system setting. */
fun MotionMode.resolve(context: Context): Boolean = when (this) {
    MotionMode.FULL -> true
    MotionMode.REDUCED -> false
    MotionMode.SYSTEM -> !context.systemAnimationsDisabled()
}

/**
 * The resolved answer for the current composition.
 *
 * Provided once at the theme boundary so no screen has to re-read a global
 * setting, and so the value is stable within a frame.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }
