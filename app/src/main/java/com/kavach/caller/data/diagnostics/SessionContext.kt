package com.kavach.caller.data.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kavach.caller.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session and device context (standard Part B).
 *
 * This is logged **once per session**, not
 * repeated on every event — so it is carried in the envelope rather than being
 * re-read per line.
 */
@Singleton
class SessionContext @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** New per app launch, as required. */
    val sessionId: String = "sess_" + UUID.randomUUID().toString().replace("-", "").take(12)

    val startedAtMs: Long = System.currentTimeMillis()

    /**
     * Elapsed-realtime at process start, so startup cost can actually be
     * measured instead of guessed.
     */
    val processStartElapsedMs: Long = android.os.Process.getStartElapsedRealtime()

    /** Emitted once, when the first screen appears. */
    @Volatile var appStartReported: Boolean = false

    /** Emitted once, when the first screen of the session appears. */
    @Volatile var firstValueReported: Boolean = false

    /**
     * Which screen the user is on. Kept here so every event carries it without
     * each call site having to know.
     */
    @Volatile var currentScreen: String = SCREEN_AT_STARTUP

    val appVersion: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault(BuildConfig.VERSION_NAME)

    val os: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /**
     * A device *class*, not a unique identifier.
     *
     * Manufacturers often already prefix the model with their own name
     * ("TECNO" + "TECNO LH8n"), which produced "TECNO TECNO LH8n" in a real
     * export. The prefix is dropped when it repeats.
     */
    val deviceModel: String = deviceLabel(Build.MANUFACTURER, Build.MODEL)

    val releaseChannel: String =
        if (BuildConfig.DEBUG) "debug" else "production"

    /**
     * A readable pseudonym for this install, format KV-XXX-XXX.
     *
     * It exists so rows from one device group together without any name,
     * account, email or device identifier being involved. Random, local, and
     * never derived from anything about the person or the phone. A reinstall
     * produces a new one.
     */
    val userCode: String = runCatching {
        val prefs = context.getSharedPreferences("kavach_user_code", Context.MODE_PRIVATE)
        prefs.getString("code", null) ?: generateUserCode().also {
            prefs.edit().putString("code", it).apply()
        }
    }.getOrDefault("KV-000-000")

    /**
     * Per-install salt for pseudonymous user ids. Random and local, so a hashed
     * id cannot be matched against another installation or a phone-number
     * dictionary.
     */
    val userSalt: String = runCatching {
        val prefs = context.getSharedPreferences("kavach_log_salt", Context.MODE_PRIVATE)
        prefs.getString("salt", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("salt", it).apply()
        }
    }.getOrDefault("kavach-fallback-salt")

    /** The envelope fields, ready for the codec. */
    val envelope: EnvelopeFields
        get() = EnvelopeFields(
            platform = "android",
            appVersion = appVersion,
            os = os,
            deviceModel = deviceModel,
            releaseChannel = releaseChannel,
            userCode = userCode,
        )

    fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * What `screen_or_route` says before any screen has appeared.
         *
         * It used to say "unknown", which a real export showed on 79% of rows —
         * technically honest and completely useless. These events belong to the
         * startup phase, so they say so.
         */
        const val SCREEN_AT_STARTUP = "app_start"

        /**
         * A readable device class from the two fields Android gives us.
         *
         * Both failure modes have been seen in real exports: naive concatenation
         * produced "TECNO TECNO LH8n", and the first attempt at de-duplicating it
         * dropped the model and reported only "TECNO". This is a pure function so
         * neither can come back unnoticed.
         */
        fun deviceLabel(manufacturer: String?, model: String?): String {
            val maker = manufacturer?.trim().orEmpty()
            val name = model?.trim().orEmpty()
            val label = when {
                name.isEmpty() -> maker
                maker.isEmpty() -> name
                // The model already carries the maker; use it whole.
                name.lowercase().startsWith(maker.lowercase()) -> name
                else -> "$maker $name"
            }
            return label.trim().ifBlank { "unknown device" }
        }
    }

    private fun generateUserCode(): String {
        // Unambiguous alphabet: no O/0, no I/1, so a code can be read off a
        // screen and typed into a dashboard without confusion.
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        fun block() = (1..3).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "KV-" + block() + "-" + block()
    }
}
