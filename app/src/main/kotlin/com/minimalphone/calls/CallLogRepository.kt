package com.minimalphone.calls

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CallEntry(
    val id: Long,
    val number: String,
    val type: Int,
    val date: Long,
    val duration: Long,
    val presentation: Int,
)

fun presentationLabel(presentation: Int): String? = when (presentation) {
    TelecomManager.PRESENTATION_RESTRICTED -> "Приватный номер"
    TelecomManager.PRESENTATION_UNKNOWN,
    TelecomManager.PRESENTATION_UNAVAILABLE -> "Неизвестный номер"
    TelecomManager.PRESENTATION_PAYPHONE -> "Таксофон"
    else -> null
}

class CallLogRepository private constructor(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun load(limit: Int = 100): Result<List<CallEntry>> = withContext(Dispatchers.IO) {
        runCatching { queryEntries(null, null, limit) }
    }

    suspend fun newMissedCalls(limit: Int = 100): Result<List<CallEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queryEntries(
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                limit,
            )
        }
    }

    suspend fun markMissedRead(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put(CallLog.Calls.NEW, 0) }
            resolver.update(
                CallLog.Calls.CONTENT_URI,
                values,
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.NEW}=1",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            )
        }.map { }
    }

    suspend fun markRead(ids: List<Long>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put(CallLog.Calls.NEW, 0) }
            ids.chunked(200).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                resolver.update(
                    CallLog.Calls.CONTENT_URI,
                    values,
                    "${CallLog.Calls._ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            }
        }.map { }
    }

    private fun queryEntries(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int,
    ): List<CallEntry> {
        val result = ArrayList<CallEntry>()
        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NUMBER_PRESENTATION,
            ),
            selection,
            selectionArgs,
            "${CallLog.Calls.DATE} DESC",
        ) ?: error("call log query failed")
        cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val presentationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)
            while (cursor.moveToNext() && result.size < limit) {
                result.add(
                    CallEntry(
                        id = cursor.getLong(idIndex),
                        number = cursor.getString(numberIndex).orEmpty(),
                        type = cursor.getInt(typeIndex),
                        date = cursor.getLong(dateIndex),
                        duration = cursor.getLong(durationIndex),
                        presentation = cursor.getInt(presentationIndex),
                    ),
                )
            }
        }
        return result
    }

    suspend fun cleanup(limit: Int = 100): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ids = ArrayList<Long>()
            val cursor = resolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            ) ?: error("call log cleanup query failed")
            cursor.use {
                var index = 0
                while (cursor.moveToNext()) {
                    if (index >= limit) ids.add(cursor.getLong(0))
                    index++
                }
            }
            ids.chunked(200).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                resolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            }
        }
    }

    suspend fun delete(ids: List<Long>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ids.chunked(200).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                resolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: CallLogRepository? = null

        fun get(context: Context): CallLogRepository =
            instance ?: synchronized(this) {
                instance ?: CallLogRepository(context.applicationContext).also { instance = it }
            }
    }
}
