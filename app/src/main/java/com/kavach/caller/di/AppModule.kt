package com.kavach.caller.di

import android.content.Context
import androidx.room.Room
import com.kavach.caller.data.local.KavachDatabase
import com.kavach.caller.data.local.dao.BlockRuleDao
import com.kavach.caller.data.local.dao.CallLogDao
import com.kavach.caller.data.local.dao.ContactDao
import com.kavach.caller.data.local.dao.RecordingDao
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KavachDatabase =
        Room.databaseBuilder(context, KavachDatabase::class.java, KavachDatabase.NAME)
            // For production, swap in SQLCipher: .openHelperFactory(SupportFactory(passphrase))
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideContactDao(db: KavachDatabase): ContactDao = db.contactDao()
    @Provides fun provideBlockRuleDao(db: KavachDatabase): BlockRuleDao = db.blockRuleDao()
    @Provides fun provideCallLogDao(db: KavachDatabase): CallLogDao = db.callLogDao()
    @Provides fun provideRecordingDao(db: KavachDatabase): RecordingDao = db.recordingDao()
    @Provides fun provideTranscriptDao(db: KavachDatabase): TranscriptDao = db.transcriptDao()

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // ASR uploads can be slow
        .build()
}
