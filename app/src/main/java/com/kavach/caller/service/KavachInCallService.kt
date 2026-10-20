package com.kavach.caller.service

import android.telecom.Call
import android.telecom.InCallService

/**
 * Minimal InCallService.
 *
 * Android requires a default-dialer app to implement an InCallService; without
 * one the "Default dialer" role can never be granted, even though the manifest
 * advertises DIAL. This class exists so the role works and so a full in-call UI
 * (mute / speaker / hold / DTMF) can be layered on top later.
 */
class KavachInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
    }
}
