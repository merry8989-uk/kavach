package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kavach.caller.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    /** Most recent first — the order a history is read in. */
    @Query("SELECT * FROM search_history ORDER BY lookedUpAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE number = :number LIMIT 1")
    suspend fun find(number: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE number = :number")
    suspend fun remove(number: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int

    /**
     * Keep the newest [keep] rows and drop the rest.
     *
     * A history with no ceiling eventually describes someone's life in detail.
     */
    @Query(
        "DELETE FROM search_history WHERE number NOT IN " +
            "(SELECT number FROM search_history ORDER BY lookedUpAt DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int)
}
