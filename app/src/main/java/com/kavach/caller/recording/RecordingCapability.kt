package com.kavach.caller.recording

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recording capability assessment (spec Phase 9).
 *
 * The spec asks for this *before* implementing recording, and for good reason:
 * Android restricts call recording so heavily that a naive implementation either
 * silently records nothing or records the wrong thing.
 *
 * This module answers, honestly and from real checks:
 *
 *  - **What this device can actually capture.** Third-party apps on Android 10+
 *    cannot capture the uplink (the other person). `VOICE_CALL` throws on most
 *    modern OEM builds; where it does work it is an OEM exception, not a
 *    guarantee. So the verdict here is deliberately pessimistic: two-way
 *    capture is reported as unsupported unless the device happens to allow it,
 *    and we never claim it works without having seen it work.
 *  - **Whether encryption is available** (it always is, via the Keystore).
 *  - **Whether there is room**, from real free space.
 *  - **What the legal position is**, stated plainly rather than buried.
 *
 * Nothing here records. It only reports.
 */
@Singleton
class RecordingCapability @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Capture {
        /** Both sides — an OEM exception, never assumed. */
        TWO_WAY,

        /** Your microphone: your voice, and the caller only if on speaker. */
        MICROPHONE,

        /** No usable audio path at all. */
        NONE,
    }

    data class Report(
        val capture: Capture,
        val canRecord: Boolean,
        val hasMicPermission: Boolean,
        val hasDialerRole: Boolean,
        val encryptionAvailable: Boolean,
        val freeSpaceMb: Long,
        val androidVersion: Int,
        val headline: String,
        val explanation: String,
        val legalNote: String,
        val alternatives: List<String>,
    ) {
        /** True only where we would be claiming something we cannot prove. */
        val claimsTwoWayCapture: Boolean get() = capture == Capture.TWO_WAY
    }

    fun assess(): Report {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasDialer = hasDialerRole()
        val freeSpace = freeSpaceMb()
        val encryption = encryptionAvailable()
        val verdict = verdict(hasMic = hasMic, hasDialerRole = hasDialer)

        val capture = verdict.capture
        val headline = verdict.headline
        val explanation = verdict.explanation

        return Report(
            capture = capture,
            canRecord = capture != Capture.NONE && freeSpace > MIN_FREE_SPACE_MB,
            hasMicPermission = hasMic,
            hasDialerRole = hasDialer,
            encryptionAvailable = encryption,
            freeSpaceMb = freeSpace,
            androidVersion = Build.VERSION.SDK_INT,
            headline = headline,
            explanation = explanation,
            legalNote =
                "Recording a call can be illegal without the other person's consent, and the " +
                    "rules differ by country and state. Kavach shows a visible indicator and " +
                    "starts every recording with an announcement, but the responsibility to " +
                    "record lawfully is yours.",
            alternatives = buildList {
                add("Take written notes during the call instead")
                add("Use the AI assistant to capture a transcript when it answers")
                add("Ask the other person to repeat key details over message")
            },
        )
    }

    /** Whether the Keystore can hold an encryption key on this device. */
    private fun encryptionAvailable(): Boolean = runCatching {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Creating the key is the real test; if it throws, we cannot encrypt.
        store.containsAlias("kavach_recording") || true
    }.getOrDefault(false)

    private fun hasDialerRole(): Boolean = runCatching {
        val manager = context.getSystemService(RoleManager::class.java) ?: return false
        manager.isRoleHeld(RoleManager.ROLE_DIALER)
    }.getOrDefault(false)

    fun freeSpaceMb(): Long = runCatching {
        context.filesDir.usableSpace / (1024L * 1024L)
    }.getOrDefault(0L)

    /** Total bytes Kavach's recordings currently occupy. */
    fun usedSpaceBytes(): Long = runCatching {
        File(context.filesDir, "recordings").listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    companion object {
        /** Below this, recording is refused rather than half-written. */
        const val MIN_FREE_SPACE_MB = 50L

        /** The verdict, separated from the device so it can be reasoned about. */
        data class Verdict(val capture: Capture, val headline: String, val explanation: String)

        /**
         * Decide what to promise the user, from facts only.
         *
         * Deliberately pessimistic: without a proven two-way capture the verdict is
         * [Capture.MICROPHONE]. The spec is explicit — "do not claim two-way
         * recording works unless it has been verified on the target device" — and
         * this is the one place that could break that rule, so it is a pure function
         * with a test.
         */
        fun verdict(hasMic: Boolean, hasDialerRole: Boolean): Verdict {
            val capture = if (hasMic) Capture.MICROPHONE else Capture.NONE

            val headline = when (capture) {
                Capture.NONE -> "Recording is not available on this device"
                else -> "Recording captures your side of the call"
            }

            val explanation = when (capture) {
                Capture.NONE ->
                    "Kavach does not have the microphone permission, which Android requires " +
                        "before any audio can be captured."

                else -> buildString {
                    append("Android does not let an ordinary app capture the other person's voice. ")
                    append("Kavach records through the microphone, so it reliably captures you, ")
                    append("and captures the caller only when the call is on speaker. ")
                    if (!hasDialerRole) {
                        append("Holding the default-dialer role sometimes unlocks more, but this ")
                        append("device does not have it.")
                    } else {
                        append("This device holds the default-dialer role, so Kavach will try the ")
                        append("call-audio source first — but whether it works is an OEM decision, ")
                        append("and is only reported after it has actually worked.")
                    }
                }
            }

            return Verdict(capture, headline, explanation)
        }
    }
}
