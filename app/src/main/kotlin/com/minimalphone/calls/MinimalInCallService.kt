package com.minimalphone.calls

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import com.minimalphone.contacts.ContactsRepository
import com.minimalphone.ui.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MinimalInCallService : InCallService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentCall: Call? = null
    private var number: String = ""
    private var name: String? = null
    private var callerLabel: String? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (call !== currentCall) return
            updateName()
            publish(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallSession.service = this
        scope.launch {
            ContactsRepository.get(this@MinimalInCallService).ensureLoaded()
            currentCall?.let { call ->
                updateName()
                publish(call.details?.state ?: Call.STATE_NEW)
            }
        }
    }

    override fun onDestroy() {
        CallNotifications.cancel(this, this)
        currentCall?.unregisterCallback(callback)
        CallSession.clear()
        CallSession.service = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val existing = currentCall
        if (existing != null && existing.details?.state != Call.STATE_DISCONNECTED) return
        currentCall = call
        CallSession.call = call
        val details = call.details
        number = details?.handle?.schemeSpecificPart.orEmpty()
        callerLabel = presentationLabel(
            details?.handlePresentation ?: TelecomManager.PRESENTATION_ALLOWED,
        )
        name = null
        updateName()
        call.registerCallback(callback)
        val state = details?.state ?: Call.STATE_NEW
        publish(state)
        if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
            CallLauncher.openScreen(this, number)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call !== currentCall) return
        call.unregisterCallback(callback)
        currentCall = null
        number = ""
        name = null
        callerLabel = null
        CallSession.clear()
        CallNotifications.cancel(this, this)
        scope.launch(Dispatchers.IO) {
            CallLogRepository.get(this@MinimalInCallService).cleanup(100)
        }
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        CallSession.setMutedState(isMuted)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallSession.setSpeakerState(audioState.route == CallAudioState.ROUTE_SPEAKER)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        super.onBringToForeground(showDialpad)
        CallLauncher.openScreen(this, number)
    }

    private fun updateName() {
        name = when {
            callerLabel != null -> callerLabel
            number.isNotBlank() -> ContactsRepository.get(this).findByKey(number, Formats.region(this))?.name
            else -> null
        }
    }

    private fun publish(state: Int) {
        CallSession.publish(number, name, state)
        CallNotifications.update(this, this, CallSession.state.value)
    }
}
