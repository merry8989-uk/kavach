package com.kavach.caller.service

import com.kavach.caller.domain.model.CallSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "what is the phone doing right now".
 *
 * Both the in-call screen and the call notification observe this, so they can
 * never disagree with each other.
 */
@Singleton
class CallSessionManager @Inject constructor() {

    private val _session = MutableStateFlow<CallSession?>(null)
    val session: StateFlow<CallSession?> = _session.asStateFlow()

    /**
     * True while Kavach's InCallService owns the call (i.e. we hold the
     * default-dialer role). The fallback call-state monitor stands down then,
     * so we never post two competing notifications.
     */
    @Volatile
    var ownedByInCallService: Boolean = false

    fun set(session: CallSession?) {
        _session.value = session
    }

    fun patch(transform: (CallSession) -> CallSession) {
        _session.value = _session.value?.let(transform)
    }

    fun clear() {
        _session.value = null
    }
}
