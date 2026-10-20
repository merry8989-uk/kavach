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
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity): Long

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): RecordingEntity?
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: TranscriptEntity): Long
}
