package com.kavach.caller.service

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * InCallService for the default-dialer role.
 *
 * It tracks the live [Call] objects so the assistant can answer and end calls
 * when Kavach owns them. Without the default-dialer role there are no calls
 * here, and the assistant falls back to TelecomManager.
 */
class KavachInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls.add(call)
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED) calls.remove(c)
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        calls.remove(call)
    }

    companion object {
        private val calls = CopyOnWriteArrayList<Call>()

        /** Answer the ringing call, if we own one. */
        fun answerCurrent(): Boolean {
            val call = calls.firstOrNull { it.state == Call.STATE_RINGING } ?: return false
            return runCatching {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            }.getOrDefault(false)
        }

        /** Hang up the active call, if we own one. */
        fun hangupCurrent(): Boolean {
            val call = calls.firstOrNull { it.state != Call.STATE_DISCONNECTED } ?: return false
            return runCatching {
                call.disconnect()
                true
            }.getOrDefault(false)
        }

        fun hasActiveCall(): Boolean = calls.any { it.state != Call.STATE_DISCONNECTED }
    }
}
