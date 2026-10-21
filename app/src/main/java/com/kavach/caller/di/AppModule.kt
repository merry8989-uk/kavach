package com.kavach.caller.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kavach.caller.data.local.KavachDatabase
import com.kavach.caller.data.local.dao.BlockRuleDao
import com.kavach.caller.data.local.dao.CallLogDao
import com.kavach.caller.data.local.dao.ContactDao
import com.kavach.caller.data.local.dao.RecordingDao
import com.kavach.caller.data.local.dao.SpeedDialDao
import com.kavach.caller.data.local.dao.TranscriptDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * v1 -> v2: add the T9 key column, and make the call log idempotent by
     * de-duplicating existing rows before adding the unique index.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts_enriched ADD COLUMN t9Key TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "DELETE FROM call_log WHERE id NOT IN " +
                    "(SELECT MIN(id) FROM call_log GROUP BY number, timestamp, type)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_call_log_number_timestamp_type " +
                    "ON call_log(number, timestamp, type)"
            )
        }
    }

    /** v2 -> v3: record which SIM account each call used. */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE call_log ADD COLUMN phoneAccountId TEXT")
        }
    }

    /** v3 -> v4: per-contact ringtone + speed-dial slots. */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contacts_enriched ADD COLUMN ringtoneUri TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS speed_dial (" +
                    "slot INTEGER NOT NULL PRIMARY KEY, " +
                    "number TEXT NOT NULL, " +
                    "label TEXT NOT NULL)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        databaseKey: com.kavach.caller.data.local.DatabaseKey,
    ): KavachDatabase {
        // A pre-encryption install has a plaintext database that SQLCipher cannot
        // read. Move it aside (never delete) before opening the encrypted one.
        com.kavach.caller.data.local.DatabaseKey.moveLegacyPlaintextAside(context)
        runCatching { System.loadLibrary("sqlcipher") }

        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
            databaseKey.passphrase(),
        )
        return Room.databaseBuilder(context, KavachDatabase::class.java, KavachDatabase.NAME)
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            // Last-resort safety net only; the real path is the migration above.
            // Swap in SQLCipher for encrypted-at-rest storage.
            // Safety net only. Real migrations exist; this exists so a schema
            // slip in the field degrades to a cache refresh instead of a crash.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides fun provideContactDao(db: KavachDatabase): ContactDao = db.contactDao()
    @Provides fun provideBlockRuleDao(db: KavachDatabase): BlockRuleDao = db.blockRuleDao()
    @Provides fun provideCallLogDao(db: KavachDatabase): CallLogDao = db.callLogDao()
    @Provides fun provideRecordingDao(db: KavachDatabase): RecordingDao = db.recordingDao()
    @Provides fun provideTranscriptDao(db: KavachDatabase): TranscriptDao = db.transcriptDao()
    @Provides fun provideSpeedDialDao(db: KavachDatabase): SpeedDialDao = db.speedDialDao()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // ASR uploads can be slow
        .build()
}
