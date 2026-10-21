package com.kavach.caller.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.data.system.SmsSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Auto-reply to incoming SMS.
 *
 * Off unless the user turns it on, and it never replies to a shortcode or to
 * itself. The honest limits are worth stating: without being the default SMS
 * app, Kavach cannot mark the conversation as read, and the reply will appear in
 * the system messaging app's thread rather than being stitched into Kavach's
 * view unless READ_SMS is granted.
 */
@AndroidEntryPoint
class SmsAutoReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var sender: SmsSender
    @Inject lateinit var diagnostics: DiagnosticsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val senderNumber = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }

        // Never auto-reply to a shortcode: those are usually services, and a
        // reply to one can cost money.
        if (senderNumber.filter { it.isDigit() }.length < 7) return

        scope.launch {
            // Read the settings *inside* the coroutine. A broadcast can start a
            // cold process, where the repository's cached snapshot is still at
            // its defaults — reading the flow waits for the real values.
            val current = settings.settings.first()

            if (!current.autoReplyEnabled) return@launch
            // Never bounce off our own reply.
            if (body.trim() == current.autoReplyText.trim()) return@launch

            if (!sender.canSend()) {
                diagnostics.warn("AutoReply", "skipped — SEND_SMS not granted")
                return@launch
            }

            val result = sender.send(senderNumber, current.autoReplyText)
            diagnostics.info(
                "AutoReply",
                when (result) {
                    is SmsSender.Result.Sent ->
                        "replied to ${DiagnosticsRepository.mask(senderNumber)}"
                    is SmsSender.Result.Failed -> "failed: ${result.reason}"
                },
            )
        }
    }
}
