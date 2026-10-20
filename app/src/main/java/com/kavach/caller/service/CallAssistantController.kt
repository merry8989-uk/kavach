package com.kavach.caller.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over the platform call controls the assistant needs: answer,
 * end, and route audio so the caller can hear the greeting.
 *
 * PLATFORM LIMIT — worth stating plainly: Android exposes **no public API to
 * inject arbitrary audio into a cellular call's uplink**. So the assistant can
 * answer the call, capture the caller's audio, and speak its greeting through
 * the speaker (the caller hears that via the phone's speaker/mic path). A
 * natural two-way AI conversation requires terminating the call on a
 * server-side telephony gateway and bridging it to the AI — see the roadmap in
 * docs/PLAN.md.
 */
@Singleton
class CallAssistantController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun answerCall(): Boolean = try {
        // Prefer a call we own (default-dialer mode), else ask Telecom.
        if (KavachInCallService.answerCurrent()) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "answerCall failed: ${t.message}")
        false
    }

    fun endCall(): Boolean = try {
        if (KavachInCallService.hangupCurrent()) {
            true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(TelecomManager::class.java)?.endCall()
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "endCall failed: ${t.message}")
        false
    }

    fun setSpeakerphoneOn(on: Boolean) {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
    }

    fun setInCommunication() {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    fun resetAudioMode() {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    private companion object {
        const val TAG = "KavachAssistant"
    }
}
