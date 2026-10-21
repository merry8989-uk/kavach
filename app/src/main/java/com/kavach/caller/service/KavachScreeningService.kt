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

    override fun onScreenCall(details: Call.Details) {
        val raw = details.handle?.schemeSpecificPart

        // Module switched off in Features: let every call through untouched.
        if (!features.isEnabled(FeatureId.CALL_SCREENING)) {
            respondToCall(details, CallResponse.Builder().setDisallowCall(false).build())
            return
        }

        scope.launch {
            val decision = decideCallAction(raw)
            // Masked number only — the diagnostics log never stores full numbers.
            diagnostics.info(
                "Screening",
                "${decision.action} ${DiagnosticsRepository.mask(raw)}" +
                    (decision.rule?.let { " rule=${it.type}" } ?: " no-rule"),
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
            respondToCall(details, response)
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}
