package com.kavach.caller.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.kavach.caller.data.system.SmsSender
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A reply typed into the notification.
 *
 * It sends and does nothing else — no thread is opened, the app is not brought to
 * the front, and the notification is not replaced. The whole point of replying
 * from a notification is not leaving what you were doing.
 *
 * `goAsync` is used because the broadcast has to send an SMS, and the receiver is
 * killed the moment `onReceive` returns. Without it the send would be a race the
 * app loses on a slow device.
 */
@AndroidEntryPoint
class MessageReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var sender: SmsSender

    @Inject lateinit var notifier: MessageNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(MessageNotifier.EXTRA_REPLY_ADDRESS) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(MessageNotifier.KEY_QUICK_REPLY)
            ?.toString()
            ?.trim()

        if (text.isNullOrEmpty()) return

        val pending = goAsync()
        try {
            sender.send(
                address = address,
                text = text,
                // Nobody is watching this send either, so no delivery report and
                // no retry — same reasoning as the auto-reply.
                deliveryReports = false,
                autoRetry = false,
            )
        } finally {
            pending.finish()
        }
    }
}
