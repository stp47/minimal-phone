package com.minimalphone.calls

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CallUiState(
    val number: String,
    val name: String?,
    val state: Int,
    val muted: Boolean = false,
    val speaker: Boolean = false,
) {
    val ringing: Boolean
        get() = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING

    val active: Boolean
        get() = state == Call.STATE_ACTIVE
}

object CallSession {
    private val _state = MutableStateFlow<CallUiState?>(null)
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    @Volatile
    var call: Call? = null

    @Volatile
    var service: MinimalInCallService? = null

    @Volatile
    var speakerOn: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var toneJob: Job? = null

    fun publish(number: String, name: String?, state: Int) {
        _state.value = CallUiState(
            number = number,
            name = name,
            state = state,
            muted = _state.value?.muted ?: false,
            speaker = speakerOn,
        )
    }

    fun setMutedState(muted: Boolean) {
        _state.update { it?.copy(muted = muted) }
    }

    fun setSpeakerState(speaker: Boolean) {
        speakerOn = speaker
        _state.update { it?.copy(speaker = speaker) }
    }

    fun clear() {
        call = null
        speakerOn = false
        _state.value = null
    }

    fun answer() {
        call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun decline() {
        call?.reject(false, null)
    }

    fun hangUp() {
        call?.disconnect()
    }

    fun toggleMute() {
        val current = _state.value ?: return
        val muted = !current.muted
        _state.value = current.copy(muted = muted)
        service?.setMuted(muted)
    }

    fun toggleSpeaker() {
        val speaker = !speakerOn
        speakerOn = speaker
        _state.update { it?.copy(speaker = speaker) }
        service?.setAudioRoute(if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    fun dtmf(char: Char) {
        val current = call ?: return
        current.playDtmfTone(char)
        toneJob?.cancel()
        toneJob = scope.launch {
            delay(180)
            current.stopDtmfTone()
        }
    }
}
