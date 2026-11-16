package com.minimalphone.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.telecom.Call
import androidx.core.content.ContextCompat
import com.minimalphone.App

object CallNotifications {
    private const val INCOMING_ID = 11
    private const val ONGOING_ID = 12

    fun update(context: Context, service: MinimalInCallService?, state: CallUiState?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (state == null || state.state == Call.STATE_DISCONNECTED) {
            cancel(context, service)
            return
        }
        val title = state.name?.takeIf { it.isNotBlank() } ?: state.number
        if (state.ringing) {
            service?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            manager.cancel(ONGOING_ID)
            if (notificationsAllowed(context)) {
                manager.notify(INCOMING_ID, incoming(context, title, state.number))
            }
        } else {
            manager.cancel(INCOMING_ID)
            val notification = ongoing(context, title, state.number)
            val started = service != null && runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } else {
                    service.startForeground(ONGOING_ID, notification)
                }
            }.isSuccess
            if (!started && notificationsAllowed(context)) {
                manager.notify(ONGOING_ID, notification)
            }
        }
    }

    fun cancel(context: Context, service: MinimalInCallService?) {
        service?.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(INCOMING_ID)
        manager.cancel(ONGOING_ID)
    }

    private fun incoming(context: Context, title: String, number: String): Notification {
        val screen = screenIntent(context, number)
        return Notification.Builder(context, App.CHANNEL_CALLS)
            .setSmallIcon(com.minimalphone.R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentIntent(screen)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    "Отклонить",
                    broadcast(context, CallActionReceiver.DECLINE, 21),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    "Ответить",
                    broadcast(context, CallActionReceiver.ANSWER, 22),
                ).build(),
            )
            .build()
    }

    private fun ongoing(context: Context, title: String, number: String): Notification =
        Notification.Builder(context, App.CHANNEL_ACTIVE_CALL)
            .setSmallIcon(com.minimalphone.R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentIntent(screenIntent(context, number))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    "Завершить",
                    broadcast(context, CallActionReceiver.HANG_UP, 23),
                ).build(),
            )
            .build()

    private fun screenIntent(context: Context, number: String): PendingIntent {
        val intent = Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CallLauncher.EXTRA_NUMBER, number)
        return PendingIntent.getActivity(
            context,
            24,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationsAllowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
