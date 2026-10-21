package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kavach.caller.data.local.entity.SpeedDialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedDialDao {
    @Query("SELECT * FROM speed_dial ORDER BY slot ASC")
    fun observeAll(): Flow<List<SpeedDialEntity>>

    @Query("SELECT * FROM speed_dial WHERE slot = :slot LIMIT 1")
    suspend fun find(slot: Int): SpeedDialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SpeedDialEntity)

    @Query("DELETE FROM speed_dial WHERE slot = :slot")
    suspend fun clear(slot: Int)
}
