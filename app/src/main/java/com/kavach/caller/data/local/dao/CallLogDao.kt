package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.local.entity.RecordingEntity
import com.kavach.caller.data.local.entity.TranscriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CallLogEntity>>

    @Query("SELECT * FROM call_log WHERE aiAnswered = 1 ORDER BY timestamp DESC")
    fun observeAiAnswered(): Flow<List<CallLogEntity>>

    // IGNORE (not REPLACE): the unique index on (number, timestamp, type)
    // makes re-syncing the system log idempotent instead of duplicating rows.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: CallLogEntity): Long

    @Query("DELETE FROM call_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Most recent call involving a number — powers the dialer's identity card. */
    @Query(
        "SELECT * FROM call_log WHERE number LIKE '%' || :key || '%' " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun lastCallFor(key: String): CallLogEntity?

    /** Recent calls whose number contains the typed digits — dialer search. */
    @Query(
        "SELECT * FROM call_log WHERE number LIKE '%' || :digits || '%' " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentMatching(digits: String, limit: Int): List<CallLogEntity>

    /** Recent interactions with one number — the contact detail timeline. */
    @Query(
        "SELECT * FROM call_log WHERE number LIKE '%' || :key || '%' " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun recentCallsFor(key: String, limit: Int): List<CallLogEntity>
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): RecordingEntity?

    /** One-shot list, used by quota enforcement and the recordings screen. */
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    suspend fun allOnce(): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recording: RecordingEntity): Long
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: TranscriptEntity): Long
}
