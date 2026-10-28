package com.kavach.caller.service

import android.content.Context
import android.media.AudioManager
import android.Manifest
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.kavach.caller.util.holds
import android.media.AudioDeviceInfo
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
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

    /**
     * The audio devices this call could be routed to.
     *
     * **A Bluetooth headset that is connected is not automatically used.** Android
     * picks a route and sometimes picks the earpiece while a paired headset sits
     * there; the user then holds the phone to their ear and wonders why the
     * headset is silent. Listing the devices lets them choose.
     *
     * Needs `BLUETOOTH_CONNECT` on Android 12+, which is why this returns an empty
     * list rather than throwing when the permission is missing.
     */
    fun communicationDevices(): List<Pair<String, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return emptyList()
            audio.availableCommunicationDevices.map { it.id.toString() to deviceLabel(it) }
        }.getOrDefault(emptyList())
    }

    /** Route call audio to one device, by the id from [communicationDevices]. */
    fun routeTo(deviceId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            val target = audio.availableCommunicationDevices.firstOrNull {
                it.id.toString() == deviceId
            } ?: return false
            audio.setCommunicationDevice(target)
        }.getOrDefault(false)
    }

    /** Hand the route back to Android. */
    fun clearRouting() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
        }
    }

    private fun deviceLabel(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        -> device.productName?.toString()?.ifEmpty { null } ?: "Bluetooth"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        else -> device.productName?.toString()?.ifEmpty { null } ?: "Audio device"
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
