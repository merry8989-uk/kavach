package com.kavach.caller.data.repo

import com.kavach.caller.data.local.dao.CallLogDao
import com.kavach.caller.data.local.dao.RecordingDao
import com.kavach.caller.data.local.dao.TranscriptDao
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.local.entity.RecordingEntity
import com.kavach.caller.data.local.entity.TranscriptEntity
import com.kavach.caller.data.system.CallLogReader
import com.kavach.caller.util.PhoneUtils
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallLogRepository @Inject constructor(
    private val dao: CallLogDao,
    private val recordingDao: RecordingDao,
    private val transcriptDao: TranscriptDao,
    private val reader: CallLogReader,
) {
    fun observeAll(): Flow<List<CallLogEntity>> = dao.observeAll()
    fun observeAiAnswered(): Flow<List<CallLogEntity>> = dao.observeAiAnswered()
    fun observeRecordings(): Flow<List<RecordingEntity>> = recordingDao.observeAll()
    fun observeTranscripts(): Flow<List<TranscriptEntity>> = transcriptDao.observeAll()

    /** Mirror the system call log locally so we can annotate it (spam, AI, recording). */
    suspend fun syncFromSystem(): Int {
        val entries = reader.readRecent()
        for (e in entries) dao.insert(e)
        return entries.size
    }

    /** The last call with this number, or null. Used by the dialer header. */
    suspend fun lastCallFor(number: String): CallLogEntity? {
        val key = PhoneUtils.subscriberKey(number)
        if (key.isEmpty()) return null
        return dao.lastCallFor(key)
    }

    suspend fun recordCall(entry: CallLogEntity): Long = dao.insert(entry)
    suspend fun saveRecording(recording: RecordingEntity): Long = recordingDao.insert(recording)
    suspend fun saveTranscript(transcript: TranscriptEntity): Long = transcriptDao.insert(transcript)
    suspend fun delete(id: Long) = dao.deleteById(id)
}
