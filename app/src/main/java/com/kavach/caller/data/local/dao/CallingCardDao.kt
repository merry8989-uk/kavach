package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kavach.caller.data.local.entity.CallingCardEntity

@Dao
interface CallingCardDao {

    /**
     * One card per number.
     *
     * Upsert on the unique key rather than insert-then-hope: saving a card twice
     * should update it, and the unique index would otherwise throw on the second
     * save and lose the edit.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CallingCardEntity)

    @Query("SELECT * FROM calling_cards WHERE contactKey = :key LIMIT 1")
    suspend fun forNumber(key: String): CallingCardEntity?

    @Query("SELECT * FROM calling_cards ORDER BY updatedAt DESC")
    suspend fun all(): List<CallingCardEntity>

    @Query("SELECT COUNT(*) FROM calling_cards")
    suspend fun count(): Int

    @Query("DELETE FROM calling_cards WHERE contactKey = :key")
    suspend fun deleteForNumber(key: String)

    @Query("UPDATE calling_cards SET enabled = :enabled WHERE contactKey = :key")
    suspend fun setEnabled(key: String, enabled: Boolean)
}
