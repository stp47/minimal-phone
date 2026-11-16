package com.minimalphone.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.minimalphone.App
import com.minimalphone.MainActivity
import com.minimalphone.contacts.ContactsRepository
import com.minimalphone.ui.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 1)
        if (count <= 0) {
            cancelMissed(context)
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    CallLogRepository.get(context).markMissedRead()
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                show(context, number)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun show(context: Context, number: String?) {
        ContactsRepository.get(context).ensureLoaded()
        if (!notificationsAllowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val region = Formats.region(context)
        val calls = CallLogRepository.get(context).newMissedCalls(100).getOrNull()
        if (calls != null && calls.isNotEmpty()) {
            val contacts = ContactsRepository.get(context)
            calls.forEach { call ->
                val name = presentationLabel(call.presentation)
                    ?: contacts.findByKey(call.number, region)?.name
                    ?: call.number.ifBlank { "Неизвестный номер" }
                val text = "$name, ${Formats.listTime(call.date)}"
                val id = notificationId(call.id)
                manager.notify(TAG_MISSED, id, notification(context, text, id))
            }
            CallLogRepository.get(context).markRead(calls.map { it.id })
            return
        }
        val name = number?.let { ContactsRepository.get(context).findByKey(it, region)?.name }
            ?: number.orEmpty().ifBlank { "Неизвестный номер" }
        val now = System.currentTimeMillis()
        val text = "$name, ${Formats.listTime(now)}"
        val id = (number.hashCode() * 31 + now).toInt()
        manager.notify(TAG_MISSED, id, notification(context, text, id))
    }

    private fun notification(context: Context, text: String, requestCode: Int): Notification {
        val open = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_RECENTS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, App.CHANNEL_MISSED)
            .setSmallIcon(com.minimalphone.R.drawable.ic_stat_call)
            .setContentTitle("Пропущенный вызов")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun notificationId(id: Long): Int = id.toInt()

    private fun notificationsAllowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun cancelMissed(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.tag == TAG_MISSED }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    companion object {
        const val TAG_MISSED = "missed"
    }
}
