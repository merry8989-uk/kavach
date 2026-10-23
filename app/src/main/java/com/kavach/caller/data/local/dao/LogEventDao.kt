package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kavach.caller.data.local.entity.LogEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reading and writing the diagnostics log.
 *
 * Everything here is paged or counted. Nothing returns the whole table, because
 * "keep every event" means the table has no ceiling — and a query that loads it
 * all is the failure the old 400-entry read limit was hiding rather than
 * preventing.
 */
@Dao
interface LogEventDao {

    @Insert
    suspend fun insert(event: LogEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<LogEventEntity>)

    /** Newest first. Paged, because the newest are what a reader wants first. */
    @Query("SELECT * FROM log_events ORDER BY ts DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<LogEventEntity>

    @Query("SELECT * FROM log_events WHERE severity = :severity ORDER BY ts DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pageBySeverity(severity: String, limit: Int, offset: Int): List<LogEventEntity>

    @Query("SELECT * FROM log_events WHERE category = :category ORDER BY ts DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pageByCategory(category: String, limit: Int, offset: Int): List<LogEventEntity>

    @Query("SELECT * FROM log_events WHERE eventName = :eventName ORDER BY ts DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pageByEventName(eventName: String, limit: Int, offset: Int): List<LogEventEntity>

    /**
     * The whole log, newest first, as raw lines.
     *
     * Only for export, and only ever written straight to a file — never held in
     * a list in memory. A cursor-shaped read is the difference between exporting
     * a year of events and running out of memory trying.
     */
    @Query("SELECT raw FROM log_events ORDER BY ts ASC, id ASC")
    suspend fun allRaw(): List<String>

    @Query("SELECT COUNT(*) FROM log_events")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM log_events WHERE severity = :severity")
    suspend fun countBySeverity(severity: String): Int

    @Query("SELECT COUNT(*) FROM log_events WHERE category = :category")
    suspend fun countByCategory(category: String): Int

    /** Drives the summary on the diagnostics screen. */
    @Query("SELECT severity, COUNT(*) AS n FROM log_events GROUP BY severity")
    suspend fun countsBySeverity(): List<SeverityCount>

    @Query("SELECT category, COUNT(*) AS n FROM log_events GROUP BY category ORDER BY n DESC")
    suspend fun countsByCategory(): List<CategoryCount>

    @Query("SELECT MIN(ts) FROM log_events")
    suspend fun oldestTs(): Long?

    @Query("SELECT MAX(ts) FROM log_events")
    suspend fun newestTs(): Long?

    @Query("SELECT COUNT(*) FROM log_events WHERE ts < :cutoff")
    suspend fun countOlderThan(cutoff: Long): Int

    /**
     * Delete everything older than [cutoff].
     *
     * Retention is a user setting, and the shipped default is **off** — nothing
     * is ever deleted. A log that quietly forgets the day you needed it is not a
     * log, it is a sample.
     */
    @Query("DELETE FROM log_events WHERE ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM log_events")
    suspend fun clear()

    /** Live count, so the screen updates as events arrive. */
    @Query("SELECT COUNT(*) FROM log_events")
    fun observeCount(): Flow<Int>
}

data class SeverityCount(val severity: String, val n: Int)

data class CategoryCount(val category: String, val n: Int)
