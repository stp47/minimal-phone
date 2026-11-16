package com.minimalphone.messages

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.minimalphone.App
import com.minimalphone.MainActivity
import com.minimalphone.contacts.ContactsRepository
import com.minimalphone.ui.Formats

object SmsNotifier {
    fun notifyIncoming(context: Context, address: String, body: String, id: Long) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = ContactsRepository.get(context).findByKey(address, Formats.region(context))?.name ?: address
        val open = PendingIntent.getActivity(
            context,
            (id % Int.MAX_VALUE).toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_CHAT)
                .setData("minimalphone://sms/chat/${Uri.encode(address)}".toUri())
                .putExtra(MainActivity.EXTRA_CHAT_ADDRESS, address)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, App.CHANNEL_MESSAGES)
            .setSmallIcon(com.minimalphone.R.drawable.ic_stat_message)
            .setContentTitle(name)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        manager.notify(tag(address), notificationId(id), notification)
    }

    fun cancelForKey(context: Context, address: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val region = Formats.region(context)
        manager.activeNotifications
            .filter { notification ->
                val tag = notification.tag ?: return@filter false
                val tagged = tag.removePrefix("sms:")
                tag.startsWith("sms:") && (
                    tagged == address ||
                        android.telephony.PhoneNumberUtils.areSamePhoneNumber(tagged, address, region) ||
                        (android.telephony.PhoneNumberUtils.normalizeNumber(tagged).isNotEmpty() &&
                            android.telephony.PhoneNumberUtils.normalizeNumber(tagged) ==
                            android.telephony.PhoneNumberUtils.normalizeNumber(address))
                    )
            }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun tag(address: String): String = "sms:$address"

    private fun notificationId(id: Long): Int =
        if (id > 0) id.toInt() else (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
}
