package com.minimalphone.messages

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsSender {
    const val ACTION_SENT = "com.minimalphone.messages.SMS_SENT"
    const val EXTRA_ID = "id"
    const val EXTRA_ADDRESS = "address"
    const val EXTRA_DATE = "date"

    fun send(context: Context, address: String, body: String, messageId: Long, date: Long): Result<Unit> =
        runCatching {
            val manager = context.getSystemService(SmsManager::class.java) ?: error("no sms manager")
            val parts = manager.divideMessage(body)
            val token = java.util.UUID.randomUUID().toString()
            val intents = parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    context,
                    (messageId % 1_000_000L).toInt() * 1000 + index,
                    Intent(context, SmsSentReceiver::class.java)
                        .setAction(ACTION_SENT)
                        .setData("minimalphone://sms/sent/$token/$index".toUri())
                        .putExtra(EXTRA_ID, messageId)
                        .putExtra(EXTRA_ADDRESS, address)
                        .putExtra(EXTRA_DATE, date),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            manager.sendMultipartTextMessage(address, null, parts, ArrayList(intents), null)
        }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSender.ACTION_SENT) return
        val id = intent.getLongExtra(SmsSender.EXTRA_ID, 0L)
        val address = intent.getStringExtra(SmsSender.EXTRA_ADDRESS).orEmpty()
        val date = intent.getLongExtra(SmsSender.EXTRA_DATE, 0L)
        if (id <= 0L) return
        if (resultCode == Activity.RESULT_OK) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsRepository.get(context).markFailed(id, address, date)
                SmsEvents.changed(id)
            } finally {
                pending.finish()
            }
        }
    }
}
