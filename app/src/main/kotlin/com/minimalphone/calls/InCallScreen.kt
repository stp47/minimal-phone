package com.minimalphone.calls

import android.telecom.Call
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val AnswerGreen = Color(0xFF2E7D32)
private val EndRed = Color(0xFFC62828)

@Composable
fun InCallScreen(
    fallbackNumber: String,
    onClose: () -> Unit,
    onProximity: (Boolean) -> Unit,
) {
    val state by CallSession.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var seen by remember { mutableStateOf(false) }
    var keypad by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state != null) seen = true else if (seen) onClose()
    }
    LaunchedEffect(Unit) {
        delay(2000)
        if (!seen && CallSession.state.value == null && CallSession.call == null) onClose()
    }
    DisposableEffect(state?.active) {
        onProximity(state?.active == true)
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { onProximity(false) }
    }

    val number = state?.number?.takeIf { it.isNotBlank() } ?: fallbackNumber
    val title = state?.name?.takeIf { it.isNotBlank() } ?: number
    val ringing = state?.ringing == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (ringing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallCircle(
                        color = EndRed,
                        contentColor = Color.White,
                        size = 76,
                        onClick = { CallSession.decline() },
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "Отклонить")
                    }
                    CallCircle(
                        color = AnswerGreen,
                        contentColor = Color.White,
                        size = 76,
                        onClick = { CallSession.answer() },
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Ответить")
                    }
                }
            } else {
                if (keypad) {
                    DtmfPad(onKey = CallSession::dtmf)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val muted = state?.muted == true
                    val speaker = state?.speaker == true
                    val selectedContainer = MaterialTheme.colorScheme.primaryContainer
                    val selectedContent = MaterialTheme.colorScheme.onPrimaryContainer
                    val neutralContainer = MaterialTheme.colorScheme.surfaceContainerHighest
                    val neutralContent = MaterialTheme.colorScheme.onSurface
                    CallCircle(
                        color = if (muted) selectedContainer else neutralContainer,
                        contentColor = if (muted) selectedContent else neutralContent,
                        size = 64,
                        onClick = { CallSession.toggleMute() },
                    ) {
                        Icon(
                            if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (muted) "Включить микрофон" else "Выключить микрофон",
                        )
                    }
                    CallCircle(
                        color = if (speaker) selectedContainer else neutralContainer,
                        contentColor = if (speaker) selectedContent else neutralContent,
                        size = 64,
                        onClick = { CallSession.toggleSpeaker() },
                    ) {
                        Icon(
                            if (speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (speaker) "Выключить динамик" else "Включить динамик",
                        )
                    }
                    CallCircle(
                        color = if (keypad) selectedContainer else neutralContainer,
                        contentColor = if (keypad) selectedContent else neutralContent,
                        size = 64,
                        onClick = { keypad = !keypad },
                    ) {
                        Icon(
                            Icons.Filled.Dialpad,
                            contentDescription = if (keypad) "Скрыть клавиатуру" else "Показать клавиатуру",
                        )
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
                CallCircle(
                    color = EndRed,
                    contentColor = Color.White,
                    size = 76,
                    onClick = { CallSession.hangUp() },
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Завершить")
                }
            }
            Spacer(modifier = Modifier.height(56.dp))
        }
    }
}

@Composable
private fun CallCircle(
    color: Color,
    contentColor: Color,
    size: Int,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
            content = content,
        )
    }
}

@Composable
private fun DtmfPad(onKey: (Char) -> Unit) {
    val keys = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('*', '0', '#'),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onKey(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = key.toString(),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 24.sp,
                        )
                    }
                }
            }
        }
    }
}
