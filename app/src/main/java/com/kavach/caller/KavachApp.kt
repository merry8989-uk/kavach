package com.kavach.caller

import android.app.Application
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.service.Announcer
import com.kavach.caller.service.CallStateMonitor
import com.kavach.caller.work.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KavachApp : Application() {

    @Inject lateinit var announcer: Announcer
    @Inject lateinit var callStateMonitor: CallStateMonitor
    @Inject lateinit var features: FeatureRegistry

    override fun onCreate() {
        super.onCreate()
        // Warm the TTS engine so announcements are instant on the first ring.
        announcer.init()
        // Periodic call-log sync (only if the feature is on) + retention cleanup.
        WorkerScheduler.schedule(this, backgroundSyncEnabled = features.isEnabled(FeatureId.BACKGROUND_SYNC))
        // Fallback call tracking (no-op until READ_PHONE_STATE is granted; the
        // nav graph retries once onboarding is done).
        callStateMonitor.start()
    }
}
