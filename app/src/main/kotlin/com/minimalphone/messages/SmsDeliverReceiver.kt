package com.minimalphone.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.minimalphone.AppState
import com.minimalphone.contacts.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
            ?: return
        if (parts.isEmpty()) return
        val address = parts.first().displayOriginatingAddress?.takeIf { it.isNotBlank() } ?: return
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        val sentAt = parts.first().timestampMillis
        val subscription = intent.getIntExtra(
            SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val stored = SmsRepository.get(context).storeIncoming(address, body, sentAt, subscription)
                val id = stored.getOrDefault(0L)
                ContactsRepository.get(context).ensureLoaded()
                if (!(AppState.isForeground && AppState.sameAddress(context, AppState.openChatAddress, address))) {
                    SmsNotifier.notifyIncoming(context, address, body, id)
                }
                SmsRepository.get(context).reloadConversations()
                SmsEvents.changed(id)
            } finally {
                pending.finish()
            }
        }
    }
}

class MmsStubReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.WAP_PUSH_DELIVER") return
    }
}

class RespondViaMessageService : android.app.Service() {
    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheme = intent?.data?.scheme
        if (scheme != "sms" && scheme != "smsto") {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val numbers = intent.data?.schemeSpecificPart
            ?.substringBefore('?')
            .orEmpty()
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (text.isNullOrBlank() || numbers.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        CoroutineScope(Dispatchers.IO).launch {
            numbers.forEach { number ->
                val stored = SmsRepository.get(this@RespondViaMessageService).storeOutgoing(number, text)
                val message = stored.getOrNull()
                if (message != null) {
                    val sent = SmsSender.send(this@RespondViaMessageService, number, text, message.id, message.date)
                    if (sent.isFailure) {
                        SmsRepository.get(this@RespondViaMessageService).markFailed(message.id, number, message.date)
                    }
                    SmsEvents.changed(message.id)
                } else {
                    SmsSender.send(this@RespondViaMessageService, number, text, 0, 0)
                }
            }
            SmsRepository.get(this@RespondViaMessageService).reloadConversations()
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
}
