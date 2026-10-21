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
 * The standard is explicit that this is logged **once per session**, not
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

    /** Startup timings, filled in by the app once it knows them. */
    @Volatile var coldStartMs: Long? = null
    @Volatile var warmStartMs: Long? = null
    @Volatile var startType: String = "cold"

    /**
     * Which screen the user is on. Kept here so every event carries it without
     * each call site having to know.
     */
    @Volatile var currentScreen: String = "unknown"

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

    /** A device *class*, not a unique identifier. */
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val releaseChannel: String =
        if (BuildConfig.DEBUG) "debug" else "production"

    /**
     * A readable pseudonym for this install, format KV-XXX-XXX.
     *
     * It exists so Notion rows from one device group together without any name,
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

    private fun generateUserCode(): String {
        // Unambiguous alphabet: no O/0, no I/1, so a code can be read off a
        // screen and typed into Notion without confusion.
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        fun block() = (1..3).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return "KV-" + block() + "-" + block()
    }
}
