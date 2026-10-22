package com.kavach.caller.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import com.kavach.caller.ai.AssistantOrchestrator
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.diagnostics.DiagnosticsRepository
import com.kavach.caller.domain.feature.FeatureId
import com.kavach.caller.domain.feature.FeatureRegistry
import com.kavach.caller.domain.usecase.DecideCallActionUseCase
import com.kavach.caller.recording.CallRecorderService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The heart of Kavach's protection: a CallScreeningService the OS invokes
 * *before* the phone rings.
 *
 * The decision itself is delegated to [DecideCallActionUseCase] so the rule
 * logic stays out of the Android service and remains unit-testable.
 */
@AndroidEntryPoint
class KavachScreeningService : CallScreeningService() {

    @Inject lateinit var decideCallAction: DecideCallActionUseCase
    @Inject lateinit var announcer: Announcer
    @Inject lateinit var assistant: AssistantOrchestrator
    @Inject lateinit var features: FeatureRegistry
    @Inject lateinit var diagnostics: DiagnosticsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards against answering the same call twice, which throws. */
    private val responded = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onScreenCall(details: Call.Details) {
        val raw = details.handle?.schemeSpecificPart

        // Module switched off in Features: let every call through untouched.
        if (!features.isEnabled(FeatureId.CALL_SCREENING)) {
            respondToCall(details, CallResponse.Builder().setDisallowCall(false).build())
            return
        }

        scope.launch {
            // Android gives a screening service about five seconds to respond,
            // and a call that gets no answer simply rings on. The decision itself
            // is local — contacts and block rules, no network — but "local" is
            // not "instant": the first query after a cold start has to open an
            // encrypted database, and anything can be slow once.
            //
            // So there is now a hard deadline, and the failure mode is
            // deliberate: if our own code is too slow, the call is **allowed**.
            // Losing a genuine call because Kavach was thinking is far worse
            // than letting one spam call through.
            val decision = withTimeoutOrNull(SCREENING_DEADLINE_MS) { decideCallAction(raw) }

            if (decision == null) {
                diagnostics.errorStateShown(
                    errorCode = "screening_timeout",
                    screen = "KavachScreeningService",
                    messageKey = "deadline_${SCREENING_DEADLINE_MS}ms",
                )
                if (respondOnce(details, CallResponse.Builder().setDisallowCall(false).build())) {
                    diagnostics.callScreened(
                        action = "ALLOW",
                        outcome = "failed",
                        hasRule = false,
                        numberType = null,
                    )
                }
                return@launch
            }

            // Intent *and* outcome together: what we decided, and whether the
            // response we built actually went through.
            diagnostics.callScreened(
                action = decision.action.name,
                outcome = "success",
                hasRule = decision.rule != null,
                numberType = decision.rule?.type?.name,
            )

            val response = when (decision.action) {
                BlockAction.BLOCK -> CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(true)
                    .build()

                BlockAction.SILENCE -> CallResponse.Builder()
                    .setDisallowCall(false)
                    .setSilenceCall(true)
                    .build()

                BlockAction.SEND_TO_AI -> {
                    // Only hand the call to the assistant if that module is on.
                    if (features.isEnabled(FeatureId.AI_ASSISTANT)) {
                        assistant.queueForAssistant(raw ?: "", details)
                        CallAssistantService.start(applicationContext, raw ?: "")
                    }
                    CallResponse.Builder().setDisallowCall(false).build()
                }

                BlockAction.ALLOW -> {
                    if (features.isEnabled(FeatureId.ANNOUNCER)) {
                        announcer.announceIncoming(raw)
                    }
                    if (features.isEnabled(FeatureId.CALL_RECORDING)) {
                        CallRecorderService.start(applicationContext, raw ?: "")
                    }
                    CallResponse.Builder().setDisallowCall(false).build()
                }
            }
            respondOnce(details, response)
        }
    }

    /**
     * Answer exactly once.
     *
     * `respondToCall` throws if it is called twice for the same call, and a
     * screening service that throws is a screening service that has stopped
     * screening. The flag makes the second attempt a no-op instead.
     */
    private fun respondOnce(details: Call.Details, response: CallResponse): Boolean {
        if (!responded.compareAndSet(false, true)) return false
        return runCatching {
            respondToCall(details, response)
            true
        }.getOrElse { throwable ->
            diagnostics.errorStateShown(
                errorCode = "screening_respond_failed",
                screen = "KavachScreeningService",
                messageKey = throwable.javaClass.simpleName,
            )
            false
        }
    }

    override fun onDestroy() {
        responded.set(false)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * Well inside Android's ~5 second window. The decision is local, so this
         * is generous for the normal case and only bites when something is badly
         * wrong — in which case the call is allowed rather than dropped.
         */
        private const val SCREENING_DEADLINE_MS = 2_500L
    }
}
