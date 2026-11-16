package com.minimalphone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CHANNEL_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            },
            NotificationChannel(CHANNEL_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_MISSED, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_ACTIVE_CALL, "Текущий вызов", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannels(channels)
    }

    companion object {
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MISSED = "missed"
        const val CHANNEL_ACTIVE_CALL = "active_call"
    }
}
