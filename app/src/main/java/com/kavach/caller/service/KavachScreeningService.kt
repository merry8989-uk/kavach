package com.kavach.caller.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import com.kavach.caller.data.local.entity.BlockAction
import com.kavach.caller.data.repo.BlockRepository
import com.kavach.caller.data.repo.ContactRepository
import com.kavach.caller.util.PhoneUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The heart of Kavach's protection: a CallScreeningService that the OS invokes
 * *before* the phone rings. It decides whether to allow, silently reject, or
 * hand the call to the AI assistant.
 *
 * Requires the user to grant the "Call screening" role.
 */
@AndroidEntryPoint
class KavachScreeningService : CallScreeningService() {

    @Inject lateinit var blockRepository: BlockRepository
    @Inject lateinit var contactRepository: ContactRepository
    @Inject lateinit var announcer: Announcer
    @Inject lateinit var assistant: AssistantOrchestrator

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
                    // Let it ring through to the assistant, which answers on-device.
                    assistant.queueForAssistant(raw ?: "", details)
                    CallResponse.Builder().setDisallowCall(false).build()
                }

                BlockAction.ALLOW -> {
                    announcer.announceIncoming(raw)
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
