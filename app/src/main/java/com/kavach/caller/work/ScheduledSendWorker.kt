package com.kavach.caller.work

import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Sending a message later.
 *
 * **Held in WorkManager rather than a table of our own.** A scheduled message has
 * to survive the app being killed and the phone rebooting, which is exactly what
 * WorkManager persists for — writing a table and then a worker to read it would be
 * the same feature with more places to be wrong, and a schema migration on top.
 *
 * The message body lives in the work's input data, so it is not duplicated
 * anywhere, and cancelling the work is cancelling the message.
 */
object ScheduledSend {

    private const val KEY_ADDRESS = "address"
    private const val KEY_TEXT = "text"
    private const val KEY_SUBSCRIPTION = "subscription"
    private const val KEY_REPORTS = "delivery_reports"
    private const val KEY_RETRY = "auto_retry"

    /** Tag prefix, so the pending list for one conversation is a single query. */
    private const val TAG_PREFIX = "scheduled_send:"

    /**
     * Queue a message for [atMillis].
     *
     * Returns the work id, which is what the UI cancels by. A past time is clamped
     * to "now" rather than refused: the user picked a time that has already gone
     * by while the dialog was open, and sending immediately is what they meant.
     */
    fun schedule(
        context: Context,
        address: String,
        text: String,
        atMillis: Long,
        subscriptionId: Int? = null,
        deliveryReports: Boolean = false,
        autoRetry: Boolean = true,
    ): java.util.UUID {
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0L)

        val data = Data.Builder()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_TEXT, text)
            .putInt(KEY_SUBSCRIPTION, subscriptionId ?: -1)
            .putBoolean(KEY_REPORTS, deliveryReports)
            .putBoolean(KEY_RETRY, autoRetry)
            .build()

        val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(TAG_PREFIX + address)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "scheduled_send:" + request.id,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request.id
    }

    /**
     * Scheduled messages still waiting for one address.
     *
     * Only ENQUEUED ones: a message that already went is not pending, and showing
     * it as pending would invite the user to cancel something that has been sent.
     */
    fun pending(context: Context, address: String): List<WorkInfo> = runCatching {
        WorkManager.getInstance(context)
            .getWorkInfosByTag(TAG_PREFIX + address)
            .get()
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    }.getOrDefault(emptyList())

    /**
     * **The body cannot be read back from WorkManager**, so the pending list shows
     * the time and a cancel button rather than a preview. Duplicating the text
     * into our own store to show it would mean two copies of a message that is
     * already sitting in the work's input data, and a cleanup path for both.
     */

    fun cancel(context: Context, id: java.util.UUID) {
        WorkManager.getInstance(context).cancelWorkById(id)
    }
}

/**
 * Delivers one scheduled message.
 *
 * The sending itself is [com.kavach.caller.data.system.SmsSender], the same path
 * a message typed now takes — a scheduled message should not have its own rules
 * about permissions, SIM selection or retry.
 */
class ScheduledSendWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val address = inputData.getString("address") ?: return Result.failure()
        val text = inputData.getString("text") ?: return Result.failure()

        val sender = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        ).smsSender()

        val subscription = inputData.getInt("subscription", -1).takeIf { it >= 0 }

        return when (
            val result = sender.send(
                address = address,
                text = text,
                subscriptionId = subscription,
                deliveryReports = inputData.getBoolean("delivery_reports", false),
                autoRetry = inputData.getBoolean("auto_retry", true),
            )
        ) {
            // The radio has it. `Queued` is a success for the worker's purposes:
            // whether the carrier accepted it arrives separately.
            is com.kavach.caller.data.system.SmsSender.Result.Queued,
            is com.kavach.caller.data.system.SmsSender.Result.Sent,
            -> Result.success()

            // Retried rather than dropped: a permission that was missing at 3am
            // may be granted by morning, and silently losing the message is worse
            // than trying again.
            is com.kavach.caller.data.system.SmsSender.Result.Failed -> Result.retry()

            // `else` rather than an exhaustive list, because a variant added to
            // SmsSender.Result later should not fail this build — and retrying is
            // the safe answer for an outcome the worker does not recognise.
            else -> Result.retry()
        }
    }
}
