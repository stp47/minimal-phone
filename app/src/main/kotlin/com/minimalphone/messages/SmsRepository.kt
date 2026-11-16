package com.minimalphone.messages

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.minimalphone.ui.Formats
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val status: Int,
) {
    val incoming: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX

    val failed: Boolean
        get() = status == Telephony.Sms.STATUS_FAILED || type == Telephony.Sms.MESSAGE_TYPE_FAILED
}

data class Conversation(
    val address: String,
    val key: String,
    val snippet: String,
    val date: Long,
)

data class StoredOutgoing(val id: Long, val date: Long)

object SmsEvents {
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val events: SharedFlow<Long> = _events.asSharedFlow()

    fun changed(id: Long) {
        _events.tryEmit(id)
    }
}

class SmsRepository private constructor(private val context: Context) {
    private val resolver = context.contentResolver
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val conversationsMutex = Mutex()
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun reloadConversations(): Result<Unit> = conversationsMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val region = Formats.region(context)
                val keys = HashMap<String, String>()
                val latest = LinkedHashMap<String, SmsMessage>()
                queryMessages().forEach { message ->
                    val key = keys.getOrPut(message.address) { Formats.canonicalKey(message.address, region) }
                    if (!latest.containsKey(key)) latest[key] = message
                }
                _conversations.value = latest.map { (key, message) ->
                    Conversation(message.address, key, message.body, message.date)
                }.sortedByDescending { it.date }
            }
        }
    }

    suspend fun loadMessages(address: String): Result<List<SmsMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val region = Formats.region(context)
            val key = Formats.canonicalKey(address, region)
            val keys = HashMap<String, String>()
            queryMessages()
                .filter { message ->
                    keys.getOrPut(message.address) {
                        Formats.canonicalKey(message.address, region)
                    } == key
                }
                .sortedWith(compareBy<SmsMessage> { it.date }.thenBy { it.id })
        }
    }

    suspend fun deleteConversation(address: String): Result<Unit> = conversationsMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val region = Formats.region(context)
                val key = Formats.canonicalKey(address, region)
                val keys = HashMap<String, String>()
                val ids = queryMessages()
                    .filter { message ->
                        keys.getOrPut(message.address) {
                            Formats.canonicalKey(message.address, region)
                        } == key
                    }
                    .map { it.id }
                val deleted = deleteIds(ids)
                if (ids.isNotEmpty() && deleted == 0) error("delete failed")
                _conversations.value = _conversations.value.filterNot { it.key == key }
            }
        }
    }

    suspend fun deleteMessage(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val deleted = resolver.delete(
                android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                null,
                null,
            )
            if (deleted == 0) error("delete failed")
        }
    }

    suspend fun storeIncoming(
        address: String,
        body: String,
        date: Long,
        subscriptionId: Int,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, if (date > 0) date else System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
            }
            val uri = resolver.insert(Telephony.Sms.CONTENT_URI, values) ?: error("insert failed")
            uri.lastPathSegment?.toLongOrNull() ?: 0L
        }
    }

    suspend fun storeOutgoing(address: String, body: String): Result<StoredOutgoing> = withContext(Dispatchers.IO) {
        runCatching {
            val date = lastOutgoingDate.updateAndGet { previous ->
                maxOf(System.currentTimeMillis(), previous + 1L)
            }
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.STATUS, 0)
            }
            val uri = resolver.insert(Telephony.Sms.CONTENT_URI, values) ?: error("insert failed")
            StoredOutgoing(uri.lastPathSegment?.toLongOrNull() ?: 0L, date)
        }
    }

    suspend fun markFailed(id: Long, address: String, date: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED) }
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID}=? AND ${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.DATE}=? AND ${Telephony.Sms.TYPE}=?",
                arrayOf(
                    id.toString(),
                    address,
                    date.toString(),
                    Telephony.Sms.MESSAGE_TYPE_SENT.toString(),
                ),
            )
        }
    }

    private fun deleteIds(ids: List<Long>): Int {
        var deleted = 0
        ids.chunked(200).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += resolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
            )
        }
        return deleted
    }

    private fun queryMessages(): List<SmsMessage> {
        val result = ArrayList<SmsMessage>()
        val cursor = resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.STATUS,
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
        ) ?: error("sms query failed")
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val statusIndex = it.getColumnIndexOrThrow(Telephony.Sms.STATUS)
            while (it.moveToNext()) {
                val address = it.getString(addressIndex).orEmpty()
                val body = it.getString(bodyIndex).orEmpty()
                val type = it.getInt(typeIndex)
                if (address.isBlank() || type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                result.add(
                    SmsMessage(
                        id = it.getLong(idIndex),
                        address = address,
                        body = body,
                        date = it.getLong(dateIndex),
                        type = type,
                        status = it.getInt(statusIndex),
                    ),
                )
            }
        }
        return result
    }

    fun addressKey(address: String): String = keyOf(address)

    private fun keyOf(address: String): String = Formats.canonicalKey(context, address)

    companion object {
        private val lastOutgoingDate = AtomicLong(0L)

        @Volatile
        private var instance: SmsRepository? = null

        fun get(context: Context): SmsRepository =
            instance ?: synchronized(this) {
                instance ?: SmsRepository(context.applicationContext).also { instance = it }
            }
    }
}
