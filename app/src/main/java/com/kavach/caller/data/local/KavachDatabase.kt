package com.kavach.caller.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kavach.caller.data.local.dao.BlockRuleDao
import com.kavach.caller.data.local.dao.CallLogDao
import com.kavach.caller.data.local.dao.ContactDao
import com.kavach.caller.data.local.dao.RecordingDao
import com.kavach.caller.data.local.dao.TranscriptDao
import com.kavach.caller.data.local.entity.BlockRuleEntity
import com.kavach.caller.data.local.entity.CallLogEntity
import com.kavach.caller.data.local.entity.ContactEntity
import com.kavach.caller.data.local.entity.RecordingEntity
import com.kavach.caller.data.local.entity.TranscriptEntity

@Database(
    entities = [
        ContactEntity::class,
        BlockRuleEntity::class,
        CallLogEntity::class,
        RecordingEntity::class,
        TranscriptEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class KavachDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun blockRuleDao(): BlockRuleDao
    abstract fun callLogDao(): CallLogDao
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        const val NAME = "kavach.db"
    }
}
