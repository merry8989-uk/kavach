package com.kavach.caller.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.domain.model.CallDirection
import com.kavach.caller.domain.model.CallPhase
import com.kavach.caller.domain.model.CallSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * InCallService for the default-dialer role (F1.5 / F4).
 *
 * It builds a [CallSession] from the real call details — caller name, number,
 * direction, SIM account, connect time — publishes it to [CallSessionManager],
 * and shows the in-call screen. That is the information the stock dialer shows
 * and that Kavach previously showed none of.
 *
 * Requires the **default dialer** role. Without it there are no calls here and
 * [CallStateMonitor] provides a reduced fallback.
 */
@AndroidEntryPoint
class KavachInCallService : InCallService() {

    @Inject lateinit var sessionManager: CallSessionManager
    @Inject lateinit var callNotifications: CallNotificationManager
    @Inject lateinit var contacts: ContactRepository
    /** Resolves a number to a name, a source and a risk score. */
    @Inject lateinit var identifyCaller: com.kavach.caller.domain.usecase.IdentifyCallerUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = CopyOnWriteArrayList<Call>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish(call)
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish(call)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionManager.ownedByInCallService = true
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        calls.add(call)
        call.registerCallback(callCallback)
        publish(call)
        showInCallUi()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        runCatching { call.unregisterCallback(callCallback) }
        calls.remove(call)
        if (calls.none { it.state != Call.STATE_DISCONNECTED }) {
            sessionManager.clear()
            callNotifications.clear()
            InCallActivity.finishIfRunning()
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        val state = audioState ?: return
        sessionManager.patch {
            it.copy(
                isMuted = state.isMuted,
                isSpeakerOn = state.route == CallAudioState.ROUTE_SPEAKER,
            )
        }
        sessionManager.session.value?.let { callNotifications.show(it) }
    }

    override fun onDestroy() {
        instance = null
        sessionManager.ownedByInCallService = false
        sessionManager.clear()
        super.onDestroy()
    }

    /** Build and publish the session from the live call, then enrich the name. */
    private fun publish(call: Call) {
        val details = call.details ?: return
        val number = details.handle?.schemeSpecificPart.orEmpty()

        val phase = when (call.state) {
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_DIALING -> CallPhase.DIALING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_DISCONNECTED -> CallPhase.ENDED
            else -> CallPhase.UNKNOWN
        }

        val direction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (details.callDirection) {
                Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
                Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
                else -> CallDirection.UNKNOWN
            }
        } else {
            CallDirection.UNKNOWN
        }

        val connectedAt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            details.connectTimeMillis.takeIf { it > 0L }
        } else {
            null
        }

        val audioState = callAudioState
        val session = CallSession(
            number = number,
            displayName = details.callerDisplayName,
            isKnownContact = details.callerDisplayName != null,
            direction = direction,
            phase = phase,
            simLabel = simLabelFor(details.accountHandle),
            connectedAtMillis = connectedAt,
            isMuted = audioState?.isMuted == true,
            isSpeakerOn = audioState?.route == CallAudioState.ROUTE_SPEAKER,
        )
        sessionManager.set(session)
        callNotifications.show(session)

        // The carrier rarely supplies a name, so resolve it ourselves — and while
        // we are here, work out what to tell the user about the caller and why.
        // The verdict is computed even when the carrier *did* supply a name,
        // because a name is not a safety judgement.
        scope.launch {
            val identity = runCatching { identifyCaller(number) }.getOrNull()
            val verdict = com.kavach.caller.domain.CallerVerdict.of(
                identity = identity,
                numberIsBlank = number.isBlank(),
            )
            // Resolved before the patch: `nameFor` suspends and `patch` takes a
            // plain lambda, so it cannot be called from inside it.
            val resolvedName = identity?.name ?: runCatching { contacts.nameFor(number) }.getOrNull()

            sessionManager.patch { current ->
                current.copy(
                    displayName = current.displayName ?: resolvedName,
                    isKnownContact = current.isKnownContact || identity?.source ==
                        com.kavach.caller.domain.model.CallerIdentity.Source.CONTACT,
                    verdict = verdict,
                )
            }
            sessionManager.session.value?.let { callNotifications.show(it) }
        }
    }

    private fun simLabelFor(handle: PhoneAccountHandle?): String? {
        if (handle == null) return null
        val telecom = getSystemService(TelecomManager::class.java) ?: return null
        return runCatching {
            telecom.getPhoneAccount(handle)?.label?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun showInCallUi() {
        val intent = Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    companion object {
        private var instance: KavachInCallService? = null

        /** Answer the ringing call, if we own one. */
        fun answerCurrent(): Boolean {
            val call = instance?.calls?.firstOrNull { it.state == Call.STATE_RINGING } ?: return false
            return runCatching {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            }.getOrDefault(false)
        }

        /** Hang up the active call, if we own one. */
        fun hangupCurrent(): Boolean {
            val call = instance?.calls?.firstOrNull { it.state != Call.STATE_DISCONNECTED }
                ?: return false
            return runCatching {
                call.disconnect()
                true
            }.getOrDefault(false)
        }

        fun hasActiveCall(): Boolean =
            instance?.calls?.any { it.state != Call.STATE_DISCONNECTED } == true

        fun setMuted(muted: Boolean) {
            runCatching { instance?.setMuted(muted) }
        }

        fun setSpeaker(on: Boolean) {
            runCatching {
                instance?.setAudioRoute(
                    if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE,
                )
            }
        }

        fun playDtmf(tone: Char) {
            instance?.calls?.firstOrNull { it.state == Call.STATE_ACTIVE }?.playDtmfTone(tone)
        }

        fun stopDtmf() {
            instance?.calls?.forEach { runCatching { it.stopDtmfTone() } }
        }

        /** Hold / unhold the active call. */
        fun holdCurrent(hold: Boolean) {
            instance?.calls
                ?.firstOrNull { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_HOLDING }
                ?.let { call ->
                    runCatching { if (hold) call.hold() else call.unhold() }
                }
        }

        /** Route call audio to a connected Bluetooth device. */
        fun routeBluetooth() {
            runCatching { instance?.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH) }
        }
    }
}
