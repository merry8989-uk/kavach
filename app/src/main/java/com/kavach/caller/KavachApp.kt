package com.kavach.caller

import android.app.Application
import com.kavach.caller.service.Announcer
import com.kavach.caller.work.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KavachApp : Application() {

    @Inject lateinit var announcer: Announcer

    override fun onCreate() {
        super.onCreate()
        // Warm the TTS engine so announcements are instant on the first ring.
        announcer.init()
        // Periodic call-log sync + local retention cleanup.
        WorkerScheduler.schedule(this)
    }
}
