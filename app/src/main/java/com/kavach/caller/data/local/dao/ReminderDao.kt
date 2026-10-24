package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.kavach.caller.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE contactKey = :key ORDER BY dueAt ASC")
    suspend fun forNumber(key: String): List<ReminderEntity>

    /** Pending only, soonest first — what the reminder list shows. */
    @Query("SELECT * FROM reminders WHERE doneAt IS NULL ORDER BY dueAt ASC")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE doneAt IS NULL ORDER BY dueAt ASC")
    fun observePending(): Flow<List<ReminderEntity>>

    /**
     * Reminders whose time has come and which have not been fired.
     *
     * `notifiedAt` is what stops the worker firing the same reminder every time
     * it runs, which is the difference between a reminder and an alarm.
     */
    @Query("SELECT * FROM reminders WHERE doneAt IS NULL AND notifiedAt IS NULL AND dueAt <= :now ORDER BY dueAt ASC")
    suspend fun due(now: Long): List<ReminderEntity>

    @Query("UPDATE reminders SET notifiedAt = :at WHERE id = :id")
    suspend fun markNotified(id: Long, at: Long)

    @Query("UPDATE reminders SET doneAt = :at WHERE id = :id")
    suspend fun markDone(id: Long, at: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM reminders WHERE doneAt IS NULL")
    suspend fun pendingCount(): Int
}
