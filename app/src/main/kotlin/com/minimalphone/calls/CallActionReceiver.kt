package com.minimalphone.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ANSWER -> CallSession.answer()
            DECLINE -> CallSession.decline()
            HANG_UP -> CallSession.hangUp()
        }
    }

    companion object {
        const val ANSWER = "com.minimalphone.calls.ANSWER"
        const val DECLINE = "com.minimalphone.calls.DECLINE"
        const val HANG_UP = "com.minimalphone.calls.HANG_UP"
    }
}
