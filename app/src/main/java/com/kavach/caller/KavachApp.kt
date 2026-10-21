package com.kavach.caller

import android.app.Application
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.service.Announcer
import com.kavach.caller.service.CallStateMonitor
import com.kavach.caller.work.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KavachApp : Application() {

    @Inject lateinit var announcer: Announcer
    @Inject lateinit var callStateMonitor: CallStateMonitor
    @Inject lateinit var features: FeatureRegistry
    @Inject lateinit var diagnostics: DiagnosticsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Warm the TTS engine so announcements are instant on the first ring.
        announcer.init()
        // Periodic call-log sync (only if the feature is on) + retention cleanup.
        WorkerScheduler.schedule(this, backgroundSyncEnabled = features.isEnabled(FeatureId.BACKGROUND_SYNC))
        // Fallback call tracking (no-op until READ_PHONE_STATE is granted; the
        // nav graph retries once onboarding is done).
        callStateMonitor.start()

        // A startup breadcrumb plus the current module states, so a shared log
        // shows exactly what configuration produced a bug.
        scope.launch {
            diagnostics.info("App", "Kavach ${BuildConfig.VERSION_NAME} started")
            FeatureId.entries.forEach { feature ->
                diagnostics.debug(
                    "Features",
                    "${feature.name}=${features.isEnabled(feature)} status=${features.stateOf(feature).status}",
                )
            }
        }
    }
}
