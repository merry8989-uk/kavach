package com.kavach.caller.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.kavach.caller.R
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.app.RemoteInput

/**
 * Notifications for incoming messages, with bubbles.
 *
 * **Only when Kavach is the default SMS app.** That condition is the whole design
 * of this class, and it is not a nicety: when Kavach is not the default, the
 * system messaging app already posts a notification for every message. Posting a
 * second one would give the user two notifications for one message, and no way to
 * tell which app is duplicating what.
 *
 * When Kavach *is* the default, the system app does not notify — nobody does — so
 * somebody has to, and that is this.
 *
 * The bubble setting is applied through the platform's bubble metadata rather
 * than a shortcut Kavach manages itself. `setBubbles` is what actually decides
 * whether a conversation floats; the metadata is only what makes it possible.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    /** True when this app is the one responsible for telling the user. */
    fun shouldNotify(): Boolean = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }.getOrDefault(false)

    /**
     * Show a message.
     *
     * Silently does nothing when Kavach is not the default SMS app, which is the
     * common case. That is deliberate rather than a guard the caller has to
     * remember.
     */
    fun notifyMessage(address: String, body: String, threadId: Long = 0L) {
        if (!shouldNotify()) return
        // Android 13 made notifications a runtime permission. Without it the
        // notify call is a silent no-op, so checking here makes the reason
        // explicit rather than leaving a message that simply never appears.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel()
        val prefs = settings.snapshot

        // A muted conversation does not notify. It still arrives, still sorts and
        // still counts as unread — muting is about being interrupted, not about
        // the message not existing.
        if (address in prefs.mutedSmsThreads) return
        val name = runCatching { PhoneUtils.pretty(address) }.getOrDefault(address)

        val person = Person.Builder()
            .setName(name)
            .setKey(address)
            .build()

        // A silent conversation gets the silent channel; the sound belongs to the
        // channel on Android 8 and up, so setSilent alone would do nothing.
        val silent = address in prefs.silentSmsThreads
        if (silent) ensureSilentChannel()

        val builder = NotificationCompat.Builder(
            context,
            if (silent) CHANNEL_SILENT else CHANNEL_MESSAGES,
        )
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(
                NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                    .addMessage(body, System.currentTimeMillis(), person),
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openThreadIntent(address))

        // Quick reply, straight from the notification.
        //
        // A RemoteInput rather than opening the app: the reason people reply from
        // a notification is that they do not want to leave what they are doing,
        // and an action that opens Kavach is not that.
        runCatching {
            val remoteInput = RemoteInput.Builder(KEY_QUICK_REPLY)
                .setLabel("Reply")
                .build()
            val replyIntent = Intent(context, MessageReplyReceiver::class.java).apply {
                putExtra(EXTRA_REPLY_ADDRESS, address)
            }
            val replyPending = PendingIntent.getBroadcast(
                context,
                address.hashCode() + 1,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_monochrome,
                    "Reply",
                    replyPending,
                ).addRemoteInput(remoteInput).build(),
            )
        }

        // Mark read, from the notification.
        //
        // Kavach cannot write "read" to the provider unless it is the default SMS
        // app, so this records the moment instead and the badge honours it — the
        // same mechanism the list's "mark all as read" already uses. Saying
        // nothing and hoping the user opens the app is worse.
        runCatching {
            val readIntent = Intent(context, MessageReplyReceiver::class.java).apply {
                action = MessageReplyReceiver.ACTION_MARK_READ
                putExtra(EXTRA_REPLY_ADDRESS, address)
            }
            val readPending = PendingIntent.getBroadcast(
                context,
                address.hashCode() + 2,
                readIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_monochrome,
                    "Mark read",
                    readPending,
                ).build(),
            )
        }

        // Bubbles. The setting decides whether this conversation is allowed to
        // float; the metadata below is only what makes it possible.
        when (prefs.smsBubbles) {
            "all" -> attachBubble(builder, address, person, shortLabel = name)
            "selected" -> {
                // "Selected" means conversations the user marked. Nothing is
                // marked by default, so this attaches nothing until they do —
                // which is what the word says.
                if (address in prefs.bubbledSmsThreads) {
                    attachBubble(builder, address, person, shortLabel = name)
                }
            }
            // "none" attaches nothing, and Android keeps it in the shade.
            else -> Unit
        }

        // Per-conversation sound. A silent conversation gets its own channel,
        // because on Android 8 and up the sound belongs to the channel and not to
        // the notification — setting it on the builder would do nothing.
        // Per-conversation, so a reply in one thread does not replace another.
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(address.hashCode(), builder.build())
        }
    }

    /** Clear a conversation's notification. Used by "mark read". */
    fun dismiss(address: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(address.hashCode())
        }
    }

    private fun attachBubble(
        builder: NotificationCompat.Builder,
        address: String,
        person: Person,
        shortLabel: String,
    ) {
        runCatching {
            builder.setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(
                    openThreadIntent(address),
                    IconCompat.createWithResource(context, R.drawable.ic_launcher_monochrome),
                )
                    .setAutoExpandBubble(false)
                    .setSuppressNotification(false)
                    .build(),
            )
        }
        // Kept for the shortcut association some launchers still use.
        runCatching {
            val shortcutId = "kavach-thread-" + address.hashCode()
            builder.setShortcutId(shortcutId)
        }
    }

    private fun openThreadIntent(address: String): PendingIntent = runCatching {
        val intent = Intent(context, com.kavach.caller.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ADDRESS, address)
        PendingIntent.getActivity(
            context,
            address.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrThrow()

    /**
     * A silent channel, created once.
     *
     * Android 8 made the channel own the sound. A notification cannot override it,
     * so a per-conversation "no sound" needs a per-conversation channel — one is
     * enough, because silence is the only per-conversation audio setting offered.
     */
    private fun ensureSilentChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_SILENT) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                "Messages (silent)",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Conversations you silenced"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "New text messages"
                enableVibration(true)
            },
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "kavach_messages"
        const val CHANNEL_SILENT = "kavach_messages_silent"
        const val EXTRA_OPEN_ADDRESS = "kavach.open_address"
        const val EXTRA_REPLY_ADDRESS = "kavach.reply_address"
        const val KEY_QUICK_REPLY = "kavach.quick_reply"
    }
}
