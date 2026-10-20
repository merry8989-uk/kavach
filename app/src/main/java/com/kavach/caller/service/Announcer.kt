package com.kavach.caller.service

import android.content.Context
import android.speech.tts.TextToSpeech
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks the caller's identity on ring (F7). Mode now comes from persisted
 * settings instead of an in-memory variable that never reached the service.
 */
@Singleton
class Announcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contacts: ContactRepository,
    private val settings: SettingsRepository,
) {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** Announcement mode, mirroring F7.2. */
    enum class Mode { ALWAYS, KNOWN_ONLY, UNKNOWN_ONLY, HEADSET_ONLY, OFF }

    private fun currentMode(): Mode =
        runCatching { Mode.valueOf(settings.snapshot.announcerMode) }.getOrDefault(Mode.KNOWN_ONLY)

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale("en", "IN")
        }
    }

    suspend fun announceIncoming(number: String?) {
        val mode = currentMode()
        if (mode == Mode.OFF) return
        val name = contacts.nameFor(number ?: "")
        val isKnown = name != null
        val shouldSpeak = when (mode) {
            Mode.ALWAYS -> true
            Mode.KNOWN_ONLY -> isKnown
            Mode.UNKNOWN_ONLY -> !isKnown
            Mode.HEADSET_ONLY -> true
            Mode.OFF -> false
        }
        if (!shouldSpeak) return

        val text = if (isKnown) "Call from $name"
        else "Call from unknown number ${spokenDigits(number)}"
        speak(text)
    }

    fun speak(text: String) {
        init()
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kavach-announce")
    }

    private fun spokenDigits(number: String?): String =
        PhoneUtils.normalize(number).trimStart('+').map { it }.joinToString(" ")

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
