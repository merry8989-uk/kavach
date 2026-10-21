package com.kavach.caller.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.system.CallLogReader
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.domain.model.CallDirection
import com.kavach.caller.domain.model.CallPhase
import com.kavach.caller.domain.model.CallSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fallback call-state monitor (F4).
 *
 * When Kavach does **not** hold the default-dialer role there is no
 * InCallService, so this listens to the platform's call-state changes and
 * publishes a [CallSession] built from the system call log — which is how we
 * still know the number, the direction and which SIM was used.
 *
 * It stands down entirely while [CallSessionManager.ownedByInCallService] is
 * true, so the accurate path always wins and we never double-notify.
 *
 * Honest limitation: without the role, the very first moments of a ringing call
 * may not be attributable, because the call-log row is written as the call
 * starts. The default-dialer path has no such gap.
 */
@Singleton
class CallStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: CallSessionManager,
    private val callNotifications: CallNotificationManager,
    private val contactRepository: ContactRepository,
    private val callLogReader: CallLogReader,
    private val features: FeatureRegistry,
    private val missedCallNotifier: MissedCallNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false
    private var telephonyCallback: Any? = null

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleState(state)
        }
    }

    /** Idempotent. No-ops without READ_PHONE_STATE rather than throwing. */
    fun start() {
        if (started) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                telephonyCallback = callback
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                telephony.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            started = true
        }
    }

    private fun handleState(state: Int) {
        if (sessionManager.ownedByInCallService) return

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> publish(CallPhase.RINGING)
            TelephonyManager.CALL_STATE_OFFHOOK -> publish(CallPhase.ACTIVE)
            TelephonyManager.CALL_STATE_IDLE -> {
                remindIfMissed()
                sessionManager.clear()
                callNotifications.clear()
            }
        }
    }

    /**
     * On hang-up, if the call just ended was missed, post an enriched reminder.
     * The call-log row exists by now, which is what makes this possible without
     * the default-dialer role.
     */
    private fun remindIfMissed() {
        // Respects both the toggle and the permissions the feature needs.
        if (!features.stateOf(FeatureId.MISSED_REMINDERS).isUsable) return
        scope.launch {
            val entry = runCatching { callLogReader.readRecent(1).firstOrNull() }.getOrNull()
                ?: return@launch
            if (entry.type != CallLog.Calls.MISSED_TYPE) return@launch
            val name = runCatching { contactRepository.nameFor(entry.number) }.getOrNull()
            missedCallNotifier.notifyMissed(name, entry.number)
        }
    }

    private fun publish(phase: CallPhase) {
        scope.launch {
            val entry = runCatching { callLogReader.readRecent(1).firstOrNull() }.getOrNull()
                ?: return@launch
            val number = entry.number
            if (number.isBlank()) return@launch

            val name = runCatching { contactRepository.nameFor(number) }.getOrNull()
            val direction = when (entry.type) {
                CallLog.Calls.INCOMING_TYPE, CallLog.Calls.MISSED_TYPE -> CallDirection.INCOMING
                CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                else -> CallDirection.UNKNOWN
            }

            val session = CallSession(
                number = number,
                displayName = name,
                isKnownContact = name != null,
                direction = direction,
                phase = phase,
                simLabel = simLabelFor(entry.phoneAccountId),
                connectedAtMillis = if (phase == CallPhase.ACTIVE) entry.timestamp else null,
            )
            sessionManager.set(session)
            callNotifications.show(session)
        }
    }

    private fun simLabelFor(accountId: String?): String? {
        if (accountId.isNullOrBlank()) return null
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        return runCatching {
            telecom.callCapablePhoneAccounts
                .firstOrNull { it.id == accountId }
                ?.let { handle ->
                    telecom.getPhoneAccount(handle)?.label?.toString()?.takeIf { it.isNotBlank() }
                }
        }.getOrNull()
    }
}
