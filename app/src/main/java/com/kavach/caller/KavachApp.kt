package com.kavach.caller

import android.app.Application
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.data.diagnostics.SessionContext
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltAndroidApp
class KavachApp : Application() {

    @Inject lateinit var announcer: Announcer
    @Inject lateinit var callStateMonitor: CallStateMonitor
    @Inject lateinit var features: FeatureRegistry
    @Inject lateinit var diagnostics: DiagnosticsRepository
    @Inject lateinit var settings: com.kavach.caller.data.settings.SettingsRepository
    @Inject lateinit var session: SessionContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Record uncaught exceptions as the `crash` event.
     *
     * The schema declared `crash` from the start, but nothing ever called it —
     * so a crash produced no row at all. That is the one event you cannot
     * afford to be missing, because its absence looks like "the app was closed".
     *
     * The write is synchronous and time-boxed: the process is already dying, and
     * a hung crash handler is worse than a missing log line.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                runBlocking {
                    withTimeoutOrNull(2_000) {
                        diagnostics.crash(
                            exceptionType = throwable.javaClass.name,
                            stackTrace = throwable.stackTraceToString(),
                            lastAction = session.currentScreen,
                        )
                    }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Warm the TTS engine so announcements are instant on the first ring.
        announcer.init()
        // Periodic call-log sync (only if the feature is on) + retention cleanup.
        WorkerScheduler.schedule(
            this,
            backgroundSyncEnabled = features.isEnabled(FeatureId.BACKGROUND_SYNC),
            dataSaver = settings.snapshot.dataSaver,
        )
        // Fallback call tracking (no-op until READ_PHONE_STATE is granted; the
        // nav graph retries once onboarding is done).
        callStateMonitor.start()

        // A startup breadcrumb plus the current module states, so a shared log
        // shows exactly what configuration produced a bug.
        scope.launch {
            // Session context is recorded once per launch, as the standard asks.
            diagnostics.sessionStart()
            // Only modules whose state actually changed since the last launch.
            // Logging all of them every time buried the real events in a real
            // export, and sampling made the snapshot misleadingly incomplete.
            diagnostics.featureSnapshot(
                FeatureId.entries.associate { feature ->
                    feature.name to "${features.isEnabled(feature)}|${features.stateOf(feature).status.name}"
                },
            )
        }

        installCrashHandler()
    }
}
