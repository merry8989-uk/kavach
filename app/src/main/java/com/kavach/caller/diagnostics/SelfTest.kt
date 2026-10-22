package com.kavach.caller.diagnostics

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kavach.caller.ai.SpamScorer
import com.kavach.caller.data.diagnostics.EventSchema
import com.kavach.caller.data.diagnostics.EventValidator
import com.kavach.caller.data.diagnostics.LogEvent
import com.kavach.caller.data.diagnostics.LogEventCodec
import com.kavach.caller.data.diagnostics.LogLevel
import com.kavach.caller.recording.RecordingCapability
import com.kavach.caller.recording.RecordingCrypto
import com.kavach.caller.sms.LinkInspector
import com.kavach.caller.sms.SenderId
import com.kavach.caller.sms.SmsClassifier
import com.kavach.caller.util.PhoneUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks the app against the device it is actually running on (spec Phase 15).
 *
 * The spec is blunt about why this exists:
 *
 *   "Do not mark a feature VERIFIED based solely on compilation."
 *
 * Every report in this project so far has carried the caveat that nothing was
 * device-verified. That caveat is honest but it is also unhelpful — it does not
 * say *which* things are unverified or how to check them.
 *
 * So this runs what can be run, and is explicit about the rest. Every check lands
 * in exactly one of four buckets:
 *
 *  - **passed** — it ran here, on this device, and the answer was right
 *  - **failed** — it ran and the answer was wrong
 *  - **not run** — it could not run at all (no permission, no hardware)
 *  - **needs device** — it cannot be checked from inside the app, and only a
 *    real call, a real message or a real recording will settle it
 *
 * The last bucket is the important one. A self-test that reported "screening
 * works" because the service is registered would be worse than no self-test at
 * all.
 */
@Singleton
class SelfTest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spamScorer: SpamScorer,
    private val recordingCrypto: RecordingCrypto,
    private val recordingCapability: RecordingCapability,
) {
    enum class Outcome { PASSED, FAILED, NOT_RUN, NEEDS_DEVICE }

    data class Check(
        val name: String,
        val outcome: Outcome,
        val detail: String,
    )

    data class Report(val checks: List<Check>) {
        val passed: List<Check> get() = checks.filter { it.outcome == Outcome.PASSED }
        val failed: List<Check> get() = checks.filter { it.outcome == Outcome.FAILED }
        val notRun: List<Check> get() = checks.filter { it.outcome == Outcome.NOT_RUN }
        val needsDevice: List<Check> get() = checks.filter { it.outcome == Outcome.NEEDS_DEVICE }

        /** True only when everything that could run, ran and passed. */
        val allRunnablePassed: Boolean get() = failed.isEmpty()
    }

    fun run(): Report {
        val checks = mutableListOf<Check>()
        checks += pureLogicChecks()
        checks += permissionChecks()
        checks += roleChecks()
        checks += storageChecks()
        checks += integrationChecks()
        checks += deviceOnlyChecks()
        return Report(checks)
    }

    // ------------------------------------------------------------ pure logic --

    /**
     * The logic that decides what the user sees. These are the checks worth
     * trusting most: they either produce the right answer on this device or they
     * do not, with nothing in between.
     */
    private fun pureLogicChecks(): List<Check> = listOf(
        expect("Number normalisation", "+91 98765 43210", "+919876543210") {
            PhoneUtils.toE164("+91 98765 43210")
        },
        expect("Number normalisation (local)", "9876543210", "+919876543210") {
            PhoneUtils.toE164("9876543210")
        },
        expect("Subscriber key ignores the country code", "+919876543210", "9876543210") {
            PhoneUtils.subscriberKey("+919876543210")
        },
        expect("A saved contact is never spam", 0, 0) {
            spamScorer.score(number = "9876543210", reports = 500, isContact = true)
        },
        expect("A heavily reported number scores high", true, true) {
            spamScorer.score(number = "9876543210", reports = 200) >= 50
        },
        expect("Sender ID resolves a known header", "HDFC Bank", "HDFC Bank") {
            SenderId.parse("VM-HDFCBK").knownBrand ?: "none"
        },
        expect("An unknown header invents no brand", "none", "none") {
            SenderId.parse("AD-ZZZZZZ").knownBrand ?: "none"
        },
        expect("A bank OTP is classified as an OTP", SmsClassifier.Category.OTP, SmsClassifier.Category.OTP) {
            SmsClassifier.classify(
                "482913 is your OTP for HDFC Bank. Do not share it with anyone. Valid for 10 minutes.",
                address = "VM-HDFCBK",
            ).category
        },
        expect("A real debit alert is not spam", false, false) {
            SmsClassifier.classify(
                "Rs.2,500.00 debited from A/c XX1234. Avl Bal Rs.12,340.50.",
                address = "VM-HDFCBK",
            ).isSpam
        },
        expect("A KYC phishing message is caught", true, true) {
            SmsClassifier.classify(
                "URGENT: your account will be blocked. Complete KYC at http://hdfcbank-kyc-update.tk/verify",
                address = "AD-ZZZZZZ",
            ).isSpam
        },
        expect("A shortened link is flagged", true, true) {
            LinkInspector.inspect("http://bit.ly/xyz").any {
                it.risks.contains(LinkInspector.Risk.SHORTENED)
            }
        },
        expect("A genuine bank domain is not flagged", false, false) {
            LinkInspector.inspect("https://www.hdfcbank.com/personal").any {
                it.risks.contains(LinkInspector.Risk.LOOKALIKE_BRAND)
            }
        },
        expect("The event catalogue is populated", true, true) {
            EventSchema.CATALOGUE.isNotEmpty()
        },
        expect("The event catalogue is internally consistent", emptyList<String>(), emptyList<String>()) {
            EventValidator.validateCatalogue()
        },
        expect("An event missing its outcome is rejected", false, false) {
            EventValidator.validate(
                "access_fix_result",
                mapOf("action_taken" to "request_role", "state_after" to "granted"),
            ).valid
        },
        expect("A complete event is accepted", true, true) {
            EventValidator.validate(
                "access_fix_result",
                mapOf(
                    "action_taken" to "request_role",
                    "state_after" to "granted",
                    "outcome" to "success",
                ),
            ).valid
        },
    )

    /** Run [block] and compare with [expected]. */
    private fun <T> expect(name: String, expected: T, @Suppress("UNUSED_PARAMETER") label: T, block: () -> T): Check =
        runCatching {
            val actual = block()
            if (actual == expected) {
                Check(name, Outcome.PASSED, "expected and got $actual")
            } else {
                Check(name, Outcome.FAILED, "expected $expected, got $actual")
            }
        }.getOrElse { throwable ->
            Check(name, Outcome.FAILED, "threw ${throwable.javaClass.simpleName}")
        }

    // ----------------------------------------------------------- permissions --

    private fun permissionChecks(): List<Check> {
        val wanted = buildList {
            add(Manifest.permission.READ_CONTACTS to "Contacts")
            add(Manifest.permission.READ_PHONE_STATE to "Phone state")
            add(Manifest.permission.READ_CALL_LOG to "Call log")
            add(Manifest.permission.RECORD_AUDIO to "Microphone")
            add(Manifest.permission.READ_SMS to "SMS")
            // POST_NOTIFICATIONS only exists from API 33. Below that, a
            // notification needs no permission at all, so there is nothing to
            // report rather than a missing grant.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS to "Notifications")
            }
        }
        return wanted.map { (permission, label) ->
            val granted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            Check(
                name = "Permission: $label",
                // A permission the user declined is not a failure of the app.
                // It is simply a feature that cannot run, which is a different
                // thing and is reported as one.
                outcome = if (granted) Outcome.PASSED else Outcome.NOT_RUN,
                detail = if (granted) "granted" else "not granted — dependent features cannot run",
            )
        }
    }

    // ----------------------------------------------------------------- roles --

    /**
     * Roles arrived in API 29. Below that there is nothing to hold, which is a
     * "not run" rather than a failure — the features that need a role simply
     * cannot exist on that device.
     */
    private fun roleChecks(): List<Check> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(
                Check(
                    "Default dialer, SMS and screening roles",
                    Outcome.NOT_RUN,
                    "roles need Android 10 (API 29); this device is API ${Build.VERSION.SDK_INT}",
                ),
            )
        }
        return listOf(
            roleCheck(RoleManager.ROLE_DIALER, "Default dialer"),
            roleCheck(RoleManager.ROLE_SMS, "Default SMS app"),
            roleCheck(RoleManager.ROLE_CALL_SCREENING, "Call screening"),
        )
    }

    private fun roleCheck(role: String, label: String): Check = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Check(label, Outcome.NOT_RUN, "roles need Android 10 or newer")
        }
        val manager = context.getSystemService(RoleManager::class.java)
        if (manager == null) {
            Check(label, Outcome.NOT_RUN, "this device has no role manager")
        } else {
            val held = manager.isRoleHeld(role)
            Check(
                label,
                if (held) Outcome.PASSED else Outcome.NOT_RUN,
                if (held) "held" else "not held — related features stay off",
            )
        }
    }.getOrElse { Check(label, Outcome.NOT_RUN, "could not be checked") }

    // --------------------------------------------------------------- storage --

    private fun storageChecks(): List<Check> {
        val out = mutableListOf<Check>()

        out += runCatching {
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val probe = File(dir, ".selftest")
            probe.writeText("kavach")
            val ok = probe.readText() == "kavach"
            probe.delete()
            Check(
                "Encrypted storage is writable",
                if (ok) Outcome.PASSED else Outcome.FAILED,
                if (ok) "wrote and read a probe file" else "the probe did not read back",
            )
        }.getOrElse { Check("Encrypted storage is writable", Outcome.FAILED, it.javaClass.simpleName) }

        out += runCatching {
            val available = recordingCapability.freeSpaceMb()
            Check(
                "Enough space to record",
                if (available > RecordingCapability.MIN_FREE_SPACE_MB) Outcome.PASSED else Outcome.NOT_RUN,
                "$available MB free",
            )
        }.getOrElse { Check("Enough space to record", Outcome.NOT_RUN, "could not be read") }

        // The Keystore is what everything else rests on: the database passphrase,
        // the recordings, the stored keys. If it cannot do a round trip here, a
        // lot of things are quietly broken.
        out += runCatching {
            val available = recordingCrypto.isAvailable()
            Check(
                "Keystore encryption round trip",
                if (available) Outcome.PASSED else Outcome.FAILED,
                if (available) "the hardware key was created and used" else "no usable Keystore key",
            )
        }.getOrElse { Check("Keystore encryption round trip", Outcome.FAILED, it.javaClass.simpleName) }

        // The diagnostics writer itself, exercised end to end.
        out += runCatching {
            val event = LogEvent(
                eventName = "session_start",
                logLevel = LogLevel.INFO,
                timestampMs = System.currentTimeMillis(),
                sessionId = "selftest",
                screenOrRoute = "SelfTest",
                properties = mapOf("start_type" to "cold"),
            )
            val line = LogEventCodec.encode(
                event,
                com.kavach.caller.data.diagnostics.EnvelopeFields(
                    appVersion = "selftest", os = "selftest", deviceModel = "selftest",
                ),
                null,
            )
            val report = EventValidator.validate(event.eventName, event.properties)
            val looksLikeJson = line.trimStart().startsWith("{") && line.contains("\"event_name\"")
            val ok = report.valid && looksLikeJson
            Check(
                "Diagnostics writer produces a valid event",
                if (ok) Outcome.PASSED else Outcome.FAILED,
                when {
                    !looksLikeJson -> "the encoded line is not JSON"
                    else -> report.problems.firstOrNull() ?: "valid against the catalogue"
                },
            )
        }.getOrElse { Check("Diagnostics writer produces a valid event", Outcome.FAILED, it.javaClass.simpleName) }

        return out
    }

    // ----------------------------------------------------------- integration --

    /**
     * Things that depend on the rest of the phone rather than on us.
     *
     * Package visibility is the one that has bitten this app before: without a
     * `<queries>` block, an Android 11+ build cannot see other apps at all, and
     * every "open in Arattai" action silently does nothing.
     */
    private fun integrationChecks(): List<Check> {
        val out = mutableListOf<Check>()

        out += packageCheck("com.android.dialer", "Can see a dialer app")
        out += packageCheck("com.aratai.chat", "Can see Arattai")
        out += packageCheck("com.friendsV", "Can see Hyped")

        out += runCatching {
            val tts = TextToSpeech(context, null)
            val languages = tts.availableLanguages?.size ?: 0
            tts.shutdown()
            Check(
                "Text-to-speech is available",
                if (languages > 0) Outcome.PASSED else Outcome.NOT_RUN,
                "$languages language(s) reported",
            )
        }.getOrElse { Check("Text-to-speech is available", Outcome.NOT_RUN, "engine not reachable") }

        out += runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val ok = audio != null
            Check(
                "Audio service is reachable",
                if (ok) Outcome.PASSED else Outcome.NOT_RUN,
                if (ok) "AudioManager obtained" else "no audio service",
            )
        }.getOrElse { Check("Audio service is reachable", Outcome.NOT_RUN, "not reachable") }

        return out
    }

    private fun packageCheck(packageName: String, label: String): Check = runCatching {
        val visible = context.packageManager.getLaunchIntentForPackage(packageName) != null ||
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess
        Check(
            label,
            if (visible) Outcome.PASSED else Outcome.NOT_RUN,
            if (visible) "$packageName is visible" else "$packageName not installed, or hidden by package visibility",
        )
    }.getOrElse { Check(label, Outcome.NOT_RUN, "could not be checked") }

    // ----------------------------------------------------------- needs device --

    /**
     * The honest list.
     *
     * None of these can be answered from inside the app. The code can be
     * registered, the service can be declared, the permission can be held — and
     * the feature can still not work, because what it depends on is the phone
     * network, the OEM's audio stack, or a decision only a person can make.
     *
     * Reporting them as "not verified" rather than "passing" is the entire point
     * of the phase.
     */
    private fun deviceOnlyChecks(): List<Check> = listOf(
        Check(
            "An incoming call shows the caller screen",
            Outcome.NEEDS_DEVICE,
            "Call this phone from another number and watch the incoming screen.",
        ),
        Check(
            "Screening actually blocks a blocked number",
            Outcome.NEEDS_DEVICE,
            "Block a number, then call from it. It must not ring.",
        ),
        Check(
            "Silence mode silences without rejecting",
            Outcome.NEEDS_DEVICE,
            "Set a rule to Silence and call from that number.",
        ),
        Check(
            "Recording captures audio and plays back",
            Outcome.NEEDS_DEVICE,
            "Record a call, then play it from Recordings. Confirm both are audible.",
        ),
        Check(
            "The announcer is audible",
            Outcome.NEEDS_DEVICE,
            "Call this phone with the announcer on and listen.",
        ),
        Check(
            "The AI assistant answers and transcribes",
            Outcome.NEEDS_DEVICE,
            "Needs a configured backend. Without one it must decline rather than pretend.",
        ),
        Check(
            "Arattai and Hyped open with the number",
            Outcome.NEEDS_DEVICE,
            "Open a number's details and use each action.",
        ),
        Check(
            "Sending an SMS works",
            Outcome.NEEDS_DEVICE,
            "Send a message from a thread and confirm it arrives.",
        ),
        Check(
            "The dialer is usable as the default dialer",
            Outcome.NEEDS_DEVICE,
            "Set Kavach as the default dialer and place a call from it.",
        ),
        Check(
            "Hindi renders correctly across screens",
            Outcome.NEEDS_DEVICE,
            "Switch the language in Settings → Appearance and walk the main screens.",
        ),
    )

    /** A one-line summary, for a screen header. */
    fun summarise(report: Report): String =
        "${report.passed.size} passed · ${report.failed.size} failed · " +
            "${report.notRun.size} not run · ${report.needsDevice.size} need a device"
}
