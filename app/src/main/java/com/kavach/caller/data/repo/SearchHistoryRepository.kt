package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.SearchHistoryDao
import com.kavach.caller.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Numbers the user has looked up.
 *
 * Two decisions worth naming:
 *
 * - **The number is the key**, so looking the same number up again moves it to the
 *   top rather than adding a row. A history that fills with one repeated lookup is
 *   a history nobody can use.
 * - **The user can clear it**, one entry at a time or all at once. A lookup
 *   history is a record of who someone was curious about, which is more revealing
 *   than it first appears, and there is no version of this feature that is
 *   acceptable without a way to erase it.
 */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val dao: SearchHistoryDao,
) {

    fun observeRecent(limit: Int = 8): Flow<List<SearchHistoryEntity>> = dao.observeRecent(limit)

    /**
     * Record a lookup. Safe to call on every open — it replaces rather than adds.
     *
     * Pruned to [MAX_ENTRIES] afterwards. A history with no ceiling is a history
     * that eventually describes someone's life in detail, and nobody asked for
     * that when they looked up a missed call.
     */
    suspend fun record(number: String, name: String?, spamScore: Int) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return
        dao.upsert(
            SearchHistoryEntity(
                number = trimmed,
                name = name,
                spamScore = spamScore,
                lookedUpAt = System.currentTimeMillis(),
            ),
        )
        dao.prune(MAX_ENTRIES)
    }

    suspend fun remove(number: String) = dao.remove(number)

    suspend fun clear() = dao.clear()

    suspend fun count(): Int = dao.count()

    companion object {
        /** How many lookups are kept. Older ones are dropped on every write. */
        const val MAX_ENTRIES = 50
    }
}
