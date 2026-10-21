package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.SpeedDialDao
import com.kavach.caller.data.local.entity.SpeedDialEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Speed-dial slots (F1.6). */
@Singleton
class SpeedDialRepository @Inject constructor(
    private val dao: SpeedDialDao,
) {
    fun observeAll(): Flow<List<SpeedDialEntity>> = dao.observeAll()

    suspend fun find(slot: Int): SpeedDialEntity? = dao.find(slot)

    suspend fun assign(slot: Int, number: String, label: String) =
        dao.upsert(SpeedDialEntity(slot = slot, number = number, label = label))

    suspend fun clear(slot: Int) = dao.clear(slot)
}
