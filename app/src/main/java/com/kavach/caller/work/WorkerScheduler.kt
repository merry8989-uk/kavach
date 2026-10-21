package com.kavach.caller.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules Kavach's background work. Unique names keep it idempotent, and
 * background sync is only scheduled when the feature is actually enabled — so
 * switching it off in Features stops the work rather than just hiding it.
 */
object WorkerScheduler {

    private const val CALL_LOG_SYNC = "kavach_call_log_sync"
    private const val CACHE_CLEANUP = "kavach_cache_cleanup"
    private const val NOTION_SYNC = "kavach_notion_sync"

    fun schedule(
        context: Context,
        backgroundSyncEnabled: Boolean = true,
        dataSaver: Boolean = false,
        notionSyncEnabled: Boolean = false,
        notionWifiOnly: Boolean = true,
    ) {
        val manager = WorkManager.getInstance(context)

        if (notionSyncEnabled) {
            // Wi-Fi-only when the user asked for it, so a metered plan is not
            // spent on telemetry.
            val notionConstraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (notionWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()
            manager.enqueueUniquePeriodicWork(
                NOTION_SYNC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<NotionSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(notionConstraints)
                    .build(),
            )
        } else {
            manager.cancelUniqueWork(NOTION_SYNC)
        }

        if (backgroundSyncEnabled) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            manager.enqueueUniquePeriodicWork(
                CALL_LOG_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                // Data saver stretches the interval rather than stopping sync,
                // so history still catches up without burning the allowance.
                PeriodicWorkRequestBuilder<CallLogSyncWorker>(
                    if (dataSaver) 24 else 6,
                    TimeUnit.HOURS,
                )
                    .setConstraints(constraints)
                    .build(),
            )
        } else {
            manager.cancelUniqueWork(CALL_LOG_SYNC)
        }

        // Retention cleanup always runs — it exists to delete data, so it is not
        // something a user should be able to switch off.
        manager.enqueueUniquePeriodicWork(
            CACHE_CLEANUP,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
