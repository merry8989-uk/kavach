package com.kavach.caller.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kavach.caller.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts_enriched ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    /** One-shot variant, for callers that need a list rather than a stream. */
    @Query("SELECT * FROM contacts_enriched ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun observeAllOnce(): List<ContactEntity>

    @Query("SELECT * FROM contacts_enriched WHERE systemId = :systemId LIMIT 1")
    suspend fun findBySystemId(systemId: Long): ContactEntity?

    @Query(
        "SELECT * FROM contacts_enriched WHERE displayName LIKE '%' || :q || '%' " +
            "OR company LIKE '%' || :q || '%' ORDER BY displayName COLLATE NOCASE ASC"
    )
    suspend fun search(q: String): List<ContactEntity>

    /**
     * Resolve a contact by the last 10 digits of one of its numbers, in SQL.
     * The old path loaded every contact and filtered in memory, once per
     * incoming call.
     */
    @Query("SELECT * FROM contacts_enriched WHERE numbers LIKE '%' || :key || '%' LIMIT 1")
    suspend fun findByNumberKey(key: String): ContactEntity?

    /** T9 name search: prefix match on the precomputed [ContactEntity.t9Key]. */
    @Query(
        "SELECT * FROM contacts_enriched WHERE t9Key LIKE :t9 || '%' " +
            "ORDER BY displayName COLLATE NOCASE ASC LIMIT 50"
    )
    suspend fun searchT9(t9: String): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity): Long

    @Update
    suspend fun update(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("SELECT COUNT(*) FROM contacts_enriched")
    suspend fun count(): Int
}
