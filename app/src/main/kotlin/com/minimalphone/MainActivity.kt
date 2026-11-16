package com.minimalphone

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.minimalphone.ui.MinimalPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow

data class MainIntent(val id: Long, val intent: Intent?)

class MainActivity : ComponentActivity() {
    private var nextIntentId = 0L
    private val incoming = MutableStateFlow(MainIntent(0L, null))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (savedInstanceState == null) {
            incoming.value = MainIntent(++nextIntentId, intent)
        }
        setContent {
            MinimalPhoneTheme {
                MainScreen(intents = incoming, initialPage = initialPage(intent))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = MainIntent(++nextIntentId, intent)
    }

    override fun onStart() {
        super.onStart()
        AppState.isForeground = true
    }

    override fun onStop() {
        AppState.isForeground = false
        super.onStop()
    }

    companion object {
        const val ACTION_OPEN_RECENTS = "com.minimalphone.OPEN_RECENTS"
        const val ACTION_OPEN_CHAT = "com.minimalphone.OPEN_CHAT"
        const val EXTRA_CHAT_ADDRESS = "com.minimalphone.CHAT_ADDRESS"
    }
}
