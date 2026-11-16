package com.minimalphone.calls

import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.minimalphone.ui.MinimalPhoneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class InCallActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            MinimalPhoneTheme {
                InCallScreen(
                    fallbackNumber = intent.getStringExtra(CallLauncher.EXTRA_NUMBER).orEmpty(),
                    onClose = { finish() },
                    onProximity = { active -> setProximity(active) },
                )
            }
        }
        scope.launch {
            var seen = false
            CallSession.state.collect { state ->
                if (state != null) {
                    seen = true
                } else if (seen) {
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setProximity(CallSession.state.value?.active == true)
    }

    override fun onPause() {
        releaseProximity(defer = true)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseProximity()
        super.onDestroy()
    }

    private fun setProximity(active: Boolean) {
        if (active) {
            if (wakeLock == null) {
                val power = getSystemService(PowerManager::class.java)
                wakeLock = power.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "minimalphone:call",
                ).apply { setReferenceCounted(false) }
            }
            wakeLock?.acquire()
        } else {
            releaseProximity()
        }
    }

    private fun releaseProximity(defer: Boolean = false) {
        wakeLock?.let {
            if (it.isHeld) {
                if (defer) {
                    it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                } else {
                    it.release()
                }
            }
        }
        wakeLock = null
    }
}
