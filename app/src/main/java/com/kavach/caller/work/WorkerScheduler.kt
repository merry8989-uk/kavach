package com.kavach.caller.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules Kavach's background work. Unique names keep it idempotent. */
object WorkerScheduler {

    private const val CALL_LOG_SYNC = "kavach_call_log_sync"
    private const val CACHE_CLEANUP = "kavach_cache_cleanup"

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)

        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        manager.enqueueUniquePeriodicWork(
            CALL_LOG_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CallLogSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(syncConstraints)
                .build(),
        )

        manager.enqueueUniquePeriodicWork(
            CACHE_CLEANUP,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
