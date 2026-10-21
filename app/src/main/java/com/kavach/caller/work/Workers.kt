package com.kavach.caller.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kavach.caller.data.notion.NotionSyncRepository
import com.kavach.caller.data.repo.CallLogRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * Dependencies workers pull from Hilt.
 *
 * Using an entry point instead of `@HiltWorker` keeps WorkManager on its default
 * initialiser — no custom factory, no manifest surgery.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun callLogRepository(): CallLogRepository
    fun notionSyncRepository(): NotionSyncRepository
}

/**
 * Pushes queued diagnostics events to the user's Notion database.
 *
 * Only scheduled while the user has the sync switched on. A failure here is
 * never fatal — the events stay queued and the next run retries, because a
 * dashboard that lags is fine and a log that is lost is not.
 */
class NotionSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        )
        val sync = entryPoint.notionSyncRepository()
        val (sent, failure) = sync.flush()
        if (failure != null && sent == 0) Result.retry() else Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }
}

/**
 * Periodically mirrors the system call log so Kavach's annotations (spam score,
 * AI brief) stay attached to the right entries. The sync is idempotent, so
 * running it repeatedly is harmless.
 */
class CallLogSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        )
        entryPoint.callLogRepository().syncFromSystem()
        Result.success()
    } catch (t: Throwable) {
        // Transient failure — let WorkManager retry with backoff.
        Result.retry()
    }
}

/**
 * Enforces local data retention: downloaded update APKs and assistant audio
 * older than [RETENTION_DAYS] are removed.
 */
class CacheCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
        val directories = listOf(
            File(applicationContext.cacheDir, "updates"),
            File(applicationContext.filesDir, "assistant"),
        )
        directories.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) runCatching { file.delete() }
            }
        }
        return Result.success()
    }

    companion object {
        const val RETENTION_DAYS = 30L
    }
}
