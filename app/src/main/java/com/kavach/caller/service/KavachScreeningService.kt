package com.kavach.caller.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import com.kavach.caller.ai.AssistantOrchestrator
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.data.settings.SettingsRepository
import com.kavach.caller.recording.CallRecorderService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The heart of Kavach's protection: a CallScreeningService the OS invokes
 * *before* the phone rings. It decides whether to allow, silently reject, hand
 * the call to the AI assistant, or allow-and-record.
 *
 * Requires the user to grant the "Call screening" role.
 */
@AndroidEntryPoint
class KavachScreeningService : CallScreeningService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var announcer: Announcer
    @Inject lateinit var assistant: AssistantOrchestrator
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(details: Call.Details) {
        val raw = details.handle?.schemeSpecificPart
        scope.launch {
            val isContact = contactRepository.nameFor(raw ?: "") != null
            val decision = blockRepository.decide(raw, isContact)

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
                    // Let it ring, then the assistant answers it itself.
                    assistant.queueForAssistant(raw ?: "", details)
                    CallAssistantService.start(applicationContext, raw ?: "")
                    CallResponse.Builder().setDisallowCall(false).build()
                }

                BlockAction.ALLOW -> {
                    announcer.announceIncoming(raw)
                    if (settings.snapshot.recordAll) {
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
