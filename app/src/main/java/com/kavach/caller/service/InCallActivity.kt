package com.kavach.caller.service

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.kavach.caller.domain.model.CallSession
import com.kavach.caller.recording.CallRecorderService
import com.kavach.caller.ui.screens.incall.InCallScreen
import com.kavach.caller.ui.theme.KavachTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The full-screen in-call experience (F1.5).
 *
 * Shown by [KavachInCallService] when Kavach holds the default-dialer role; the
 * call notification can also open it, in which case it renders whatever the
 * fallback monitor knows.
 */
@AndroidEntryPoint
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        running = this

        setContent {
            val viewModel: InCallViewModel = hiltViewModel()
            val session by viewModel.session.collectAsState()
            KavachTheme {
                InCallScreen(
                    session = session,
                    onAnswer = { KavachInCallService.answerCurrent() },
                    onHangup = {
                        KavachInCallService.hangupCurrent()
                        finish()
                    },
                    onToggleMute = { muted -> KavachInCallService.setMuted(muted) },
                    onToggleSpeaker = { on -> KavachInCallService.setSpeaker(on) },
                    onDtmf = { tone -> KavachInCallService.playDtmf(tone) },
                    onDtmfStop = { KavachInCallService.stopDtmf() },
                    onHold = { hold -> KavachInCallService.holdCurrent(hold) },
                    onBluetooth = {
                        if (!KavachInCallService.routeBluetooth()) {
                            android.widget.Toast.makeText(
                                this,
                                "No Bluetooth device is connected to this call.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onRecord = { number -> CallRecorderService.start(this, number) },
                )
            }
        }
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private var running: InCallActivity? = null

        fun finishIfRunning() {
            running?.let { activity -> runCatching { activity.finish() } }
        }
    }
}

@HiltViewModel
class InCallViewModel @Inject constructor(
    sessionManager: CallSessionManager,
) : ViewModel() {
    val session: StateFlow<CallSession?> = sessionManager.session
}
