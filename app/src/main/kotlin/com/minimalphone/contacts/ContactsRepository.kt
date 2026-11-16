package com.minimalphone.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.telephony.PhoneNumberUtils
import com.google.i18n.phonenumbers.PhoneNumberUtil
import androidx.core.content.ContextCompat
import com.minimalphone.ui.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class Contact(
    val id: Long,
    val rawId: Long,
    val name: String,
    val number: String,
    val numbers: List<String>,
    val hasName: Boolean,
)

sealed interface ImportResult {
    data object Success : ImportResult
    data object NoContacts : ImportResult
    data object Error : ImportResult
}

sealed interface ExportResult {
    data object Success : ExportResult
    data object NoContacts : ExportResult
    data object Error : ExportResult
}

sealed interface ContactWriteResult {
    data object Success : ContactWriteResult
    data object ReloadError : ContactWriteResult
    data object WriteError : ContactWriteResult
}

class ContactsRepository private constructor(private val context: Context) {
    private val resolver = context.contentResolver
    private val util = PhoneNumberUtil.getInstance()
    private val reloadMutex = Mutex()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var contactIndex: Map<String, Contact> = emptyMap()

    suspend fun ensureLoaded(force: Boolean = false): Result<Unit> = reloadMutex.withLock {
        if (!hasReadPermission()) {
            invalidate()
            return@withLock Result.failure(SecurityException("READ_CONTACTS"))
        }
        if (loaded && !force) {
            Result.success(Unit)
        } else {
            withContext(Dispatchers.IO) {
                runCatching { loadInternal() }.onFailure { loaded = false }
            }
        }
    }

    suspend fun reload(): Result<Unit> = reloadMutex.withLock {
        if (!hasReadPermission()) {
            invalidate()
            return@withLock Result.failure(SecurityException("READ_CONTACTS"))
        }
        withContext(Dispatchers.IO) {
            runCatching { loadInternal() }.onFailure { loaded = false }
        }
    }

    fun invalidate() {
        loaded = false
        contactIndex = emptyMap()
        _contacts.value = emptyList()
    }

    private fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun loadInternal() {
        val region = Formats.region(context)
        val localAccountType = runCatching { RawContacts.getLocalAccountType(context) }.getOrNull()
        val localAccountName = runCatching { RawContacts.getLocalAccountName(context) }.getOrNull()
        val rawByContact = HashMap<Long, MutableList<Long>>()
        val localRawIds = HashSet<Long>()
        val localContacts = HashSet<Long>()

        val rawCursor = resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(
                RawContacts._ID,
                RawContacts.CONTACT_ID,
                RawContacts.ACCOUNT_TYPE,
                RawContacts.ACCOUNT_NAME,
            ),
            "${RawContacts.DELETED}=0",
            null,
            null,
        ) ?: error("raw contacts query failed")
        rawCursor.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(RawContacts._ID)
            val contactIndex = cursor.getColumnIndexOrThrow(RawContacts.CONTACT_ID)
            val typeIndex = cursor.getColumnIndexOrThrow(RawContacts.ACCOUNT_TYPE)
            val nameIndex = cursor.getColumnIndexOrThrow(RawContacts.ACCOUNT_NAME)
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(contactIndex)
                val rawId = cursor.getLong(idIndex)
                val accountType = cursor.getString(typeIndex)
                val accountName = cursor.getString(nameIndex)
                val raw = rawByContact.getOrPut(contactId) { ArrayList() }
                raw.add(rawId)
                val local = (accountType == null && accountName == null) ||
                    (localAccountType != null && localAccountName != null &&
                        accountType == localAccountType && accountName == localAccountName)
                if (local) localRawIds.add(rawId)
            }
        }

        rawByContact.forEach { (contactId, raws) ->
            if (raws.all { it in localRawIds }) localContacts.add(contactId)
        }
        if (localContacts.isEmpty()) {
            contactIndex = emptyMap()
            _contacts.value = emptyList()
            loaded = true
            return
        }

        val names = HashMap<Long, String>()
        localContacts.chunked(700).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val nameCursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts._ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
                null,
            ) ?: error("names query failed")
            nameCursor.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (cursor.moveToNext()) {
                    names[cursor.getLong(idIndex)] = cursor.getString(nameIndex).orEmpty()
                }
            }
        }

        val rawToContact = HashMap<Long, Long>()
        rawByContact.forEach { (contactId, raws) ->
            if (contactId in localContacts) raws.forEach { rawToContact[it] = contactId }
        }
        val phonesByContact = HashMap<Long, MutableList<PhoneRow>>()

        rawToContact.keys.toList().chunked(700).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val phoneCursor = resolver.query(
                Data.CONTENT_URI,
                arrayOf(
                    Data._ID,
                    Data.RAW_CONTACT_ID,
                    Data.DATA1,
                    Data.IS_PRIMARY,
                    Data.IS_SUPER_PRIMARY,
                ),
                "${Data.MIMETYPE}=? AND ${Data.DELETED}=0 AND ${Data.RAW_CONTACT_ID} IN ($placeholders)",
                (listOf(Phone.CONTENT_ITEM_TYPE) + chunk.map { it.toString() }).toTypedArray(),
                null,
            ) ?: error("phones query failed")
            phoneCursor.use { cursor ->
                val rawIndex = cursor.getColumnIndexOrThrow(Data.RAW_CONTACT_ID)
                val numberIndex = cursor.getColumnIndexOrThrow(Data.DATA1)
                val primaryIndex = cursor.getColumnIndexOrThrow(Data.IS_PRIMARY)
                val superIndex = cursor.getColumnIndexOrThrow(Data.IS_SUPER_PRIMARY)
                while (cursor.moveToNext()) {
                    val rawId = cursor.getLong(rawIndex)
                    val contactId = rawToContact[rawId] ?: continue
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    phonesByContact.getOrPut(contactId) { ArrayList() }.add(
                        PhoneRow(
                            rawId = rawId,
                            number = number,
                            primary = cursor.getInt(primaryIndex) != 0,
                            superPrimary = cursor.getInt(superIndex) != 0,
                        ),
                    )
                }
            }
        }

        val result = ArrayList<Contact>()
        names.forEach { (contactId, displayName) ->
            val raws = rawByContact[contactId] ?: return@forEach
            val phones = phonesByContact[contactId] ?: return@forEach
            val valid = phones.filter { isValid(it.number, region) }
            if (valid.isEmpty()) return@forEach
            val chosen = valid.firstOrNull { it.superPrimary }
                ?: valid.firstOrNull { it.primary }
                ?: valid.first()
            val trimmed = displayName.trim()
            val name = if (trimmed.isEmpty()) chosen.number else trimmed
            result.add(
                Contact(
                    id = contactId,
                    rawId = raws.minOrNull() ?: chosen.rawId,
                    name = name,
                    number = chosen.number,
                    numbers = valid.map { it.number }.distinct(),
                    hasName = trimmed.isNotEmpty(),
                ),
            )
        }

        val sortCollator = Collator.getInstance(Locale.forLanguageTag("ru")).apply {
            strength = Collator.SECONDARY
        }
        result.sortWith { first, second -> sortCollator.compare(first.name, second.name) }
        val index = HashMap<String, Contact>()
        result.forEach { contact ->
            contact.numbers.ifEmpty { listOf(contact.number) }.forEach { number ->
                val key = Formats.canonicalKey(number, region)
                if (key.isNotEmpty()) index.putIfAbsent(key, contact)
            }
        }
        contactIndex = index
        _contacts.value = result
        loaded = true
    }

    private fun isValid(number: String, region: String): Boolean {
        val parsed = runCatching { util.parse(number, region) }.getOrNull() ?: return false
        return runCatching { util.isValidNumber(parsed) }.getOrDefault(false)
    }

    fun find(number: String?): Contact? = find(number, Formats.region(context))

    fun find(number: String?, region: String): Contact? {
        if (number.isNullOrBlank() || !hasReadPermission()) return null
        val value = number
        return _contacts.value.firstOrNull { contact ->
            contact.numbers.ifEmpty { listOf(contact.number) }
                .any { sameNumber(it, value, region) }
        }
    }

    fun findByKey(number: String, region: String): Contact? {
        if (number.isBlank() || !hasReadPermission()) return null
        return contactIndex[Formats.canonicalKey(number, region)]
    }

    private fun sameNumber(first: String, second: String, region: String): Boolean {
        if (PhoneNumberUtils.areSamePhoneNumber(first, second, region)) return true
        val firstNormalized = PhoneNumberUtils.normalizeNumber(first)
        val secondNormalized = PhoneNumberUtils.normalizeNumber(second)
        return firstNormalized.isNotEmpty() && firstNormalized == secondNormalized
    }

    fun byId(id: Long): Contact? {
        if (!hasReadPermission()) return null
        return _contacts.value.firstOrNull { it.id == id }
    }

    suspend fun create(name: String, number: String): ContactWriteResult {
        if (createRaw(name, number).isFailure) return ContactWriteResult.WriteError
        return if (reload().isSuccess) ContactWriteResult.Success else ContactWriteResult.ReloadError
    }

    private suspend fun createRaw(name: String, number: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val localType = RawContacts.getLocalAccountType(context)
            val localName = RawContacts.getLocalAccountName(context)
            val operations = arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, localType)
                    .withValue(RawContacts.ACCOUNT_NAME, localName)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, number)
                    .build(),
            )
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }.map { }
    }

    suspend fun update(contact: Contact, name: String, number: String): ContactWriteResult {
        val apply = withContext(Dispatchers.IO) {
            runCatching {
                val ops = ArrayList<ContentProviderOperation>()
                var nameRow: Long? = null
                var phoneDataRow: Long? = null
                var phoneRank = 0
                val dataCursor = resolver.query(
                    Data.CONTENT_URI,
                    arrayOf(
                        Data._ID,
                        Data.MIMETYPE,
                        Data.IS_PRIMARY,
                        Data.IS_SUPER_PRIMARY,
                    ),
                    "${Data.RAW_CONTACT_ID}=? AND ${Data.DELETED}=0",
                    arrayOf(contact.rawId.toString()),
                    null,
                ) ?: error("contact data query failed")
                dataCursor.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(Data._ID)
                    val typeIndex = cursor.getColumnIndexOrThrow(Data.MIMETYPE)
                    val primaryIndex = cursor.getColumnIndexOrThrow(Data.IS_PRIMARY)
                    val superIndex = cursor.getColumnIndexOrThrow(Data.IS_SUPER_PRIMARY)
                    while (cursor.moveToNext()) {
                        when (cursor.getString(typeIndex)) {
                            StructuredName.CONTENT_ITEM_TYPE -> if (nameRow == null) nameRow = cursor.getLong(idIndex)
                            Phone.CONTENT_ITEM_TYPE -> {
                                val rank = when {
                                    cursor.getInt(superIndex) != 0 -> 3
                                    cursor.getInt(primaryIndex) != 0 -> 2
                                    else -> 1
                                }
                                if (rank > phoneRank) {
                                    phoneRank = rank
                                    phoneDataRow = cursor.getLong(idIndex)
                                }
                            }
                        }
                    }
                }
                val existingName = nameRow
                if (existingName != null) {
                    ops.add(
                        ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, existingName))
                            .withValue(StructuredName.DISPLAY_NAME, name)
                            .build(),
                    )
                } else {
                    ops.add(
                        ContentProviderOperation.newInsert(Data.CONTENT_URI)
                            .withValue(Data.RAW_CONTACT_ID, contact.rawId)
                            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                            .withValue(StructuredName.DISPLAY_NAME, name)
                            .build(),
                    )
                }
                val existingPhone = phoneDataRow
                if (existingPhone != null) {
                    ops.add(
                        ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, existingPhone))
                            .withValue(Phone.NUMBER, number)
                            .build(),
                    )
                } else {
                    ops.add(
                        ContentProviderOperation.newInsert(Data.CONTENT_URI)
                            .withValue(Data.RAW_CONTACT_ID, contact.rawId)
                            .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                            .withValue(Phone.NUMBER, number)
                            .build(),
                    )
                }
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }
        }
        if (apply.isFailure) return ContactWriteResult.WriteError
        return if (reload().isSuccess) ContactWriteResult.Success else ContactWriteResult.ReloadError
    }

    suspend fun delete(contact: Contact): ContactWriteResult {
        val apply = withContext(Dispatchers.IO) {
            runCatching {
                resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contact.id), null, null)
            }
        }
        if (apply.isFailure) return ContactWriteResult.WriteError
        return if (reload().isSuccess) ContactWriteResult.Success else ContactWriteResult.ReloadError
    }

    suspend fun import(uri: Uri): ImportResult {
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: return@runCatching null
                VCardParser.parse(text)
            }.getOrNull()
        } ?: return ImportResult.Error
        if (parsed.isEmpty()) return ImportResult.NoContacts
        if (ensureLoaded().isFailure) return ImportResult.Error

        var applied = false
        var failed = false
        withContext(Dispatchers.IO) {
            val region = Formats.region(context)
            val importIndex = HashMap<String, MutableList<Contact>>()
            _contacts.value.forEach { contact ->
                contact.numbers.ifEmpty { listOf(contact.number) }.forEach { number ->
                    val key = Formats.canonicalKey(number, region)
                    if (key.isNotEmpty()) {
                        val list = importIndex.getOrPut(key) { ArrayList() }
                        if (list.none { it.id == contact.id }) list.add(contact)
                    }
                }
            }
            val unique = LinkedHashMap<String, Pair<String, String>>()
            parsed.forEach { record ->
                val number = record.numbers.firstOrNull { isValid(it, region) } ?: return@forEach
                val key = Formats.canonicalKey(number, region)
                val previous = unique[key]
                unique[key] = record.name to (previous?.second ?: number)
            }
            unique.forEach { (key, entry) ->
                val name = entry.first
                val number = entry.second
                val matching = importIndex[key].orEmpty()
                    .filter { contact ->
                        contact.numbers.ifEmpty { listOf(contact.number) }
                            .any { sameNumber(it, number, region) }
                    }
                if (matching.isEmpty()) {
                    if (createRaw(name, number).isSuccess) {
                        applied = true
                    } else {
                        failed = true
                    }
                } else {
                    matching.forEach { contact ->
                        val updated = updateName(contact.rawId, name)
                        if (updated.isSuccess) applied = true else failed = true
                    }
                }
            }
        }
        if (failed) {
            reload()
            return ImportResult.Error
        }
        if (!applied) return ImportResult.NoContacts
        val reloaded = reload()
        return if (reloaded.isSuccess) ImportResult.Success else ImportResult.Error
    }

    private suspend fun updateName(rawId: Long, name: String): Result<Unit> {
        val apply = withContext(Dispatchers.IO) {
            runCatching {
                var nameRow: Long? = null
                val nameCursor = resolver.query(
                    Data.CONTENT_URI,
                    arrayOf(Data._ID),
                    "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND ${Data.DELETED}=0",
                    arrayOf(rawId.toString(), StructuredName.CONTENT_ITEM_TYPE),
                    null,
                ) ?: error("contact name query failed")
                nameCursor.use { if (it.moveToFirst()) nameRow = it.getLong(0) }
                val existingName = nameRow
                val operation = if (existingName != null) {
                    ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, existingName))
                        .withValue(StructuredName.DISPLAY_NAME, name)
                        .build()
                } else {
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, rawId)
                        .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(StructuredName.DISPLAY_NAME, name)
                        .build()
                }
                resolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
            }.map { }
        }
        return apply
    }

    suspend fun export(uri: Uri, contacts: List<Contact>): ExportResult {
        if (contacts.isEmpty()) return ExportResult.NoContacts
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = buildString {
                    contacts.forEach { contact ->
                        appendVCardLine(this, "BEGIN:VCARD")
                        appendVCardLine(this, "VERSION:3.0")
                        appendVCardLine(this, "N:;;;;")
                        appendVCardLine(this, "FN:" + escape(contact.name))
                        appendVCardLine(this, "TEL;TYPE=CELL:" + escape(contact.number))
                        appendVCardLine(this, "END:VCARD")
                    }
                }
                resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("no stream")
            }.fold(
                onSuccess = { ExportResult.Success },
                onFailure = { ExportResult.Error },
            )
        }
    }

    private fun appendVCardLine(builder: StringBuilder, line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) {
            builder.append(line).append("\r\n")
            return
        }
        var offset = 0
        var first = true
        while (offset < bytes.size) {
            val capacity = if (first) 75 else 74
            var end = minOf(offset + capacity, bytes.size)
            while (end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
            if (end == offset) end = minOf(offset + capacity, bytes.size)
            if (!first) builder.append(' ')
            builder.append(String(bytes, offset, end - offset, Charsets.UTF_8)).append("\r\n")
            offset = end
            first = false
        }
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    private data class PhoneRow(
        val rawId: Long,
        val number: String,
        val primary: Boolean,
        val superPrimary: Boolean,
    )

    companion object {
        @Volatile
        private var instance: ContactsRepository? = null

        fun get(context: Context): ContactsRepository =
            instance ?: synchronized(this) {
                instance ?: ContactsRepository(context.applicationContext).also { instance = it }
            }
    }
}

private data class VCardRecord(val name: String, val numbers: List<String>)

private object VCardParser {
    fun parse(text: String): List<VCardRecord> {
        val records = ArrayList<VCardRecord>()
        var inCard = false
        var name = ""
        var structuredName = ""
        val numbers = ArrayList<String>()

        unfold(text.removePrefix("\uFEFF")).forEach { line ->
            when {
                line.equals("BEGIN:VCARD", ignoreCase = true) -> {
                    inCard = true
                    name = ""
                    structuredName = ""
                    numbers.clear()
                }
                line.equals("END:VCARD", ignoreCase = true) -> {
                    if (inCard) {
                        val finalName = name.ifBlank { structuredName }.trim()
                        if (finalName.isNotEmpty() && numbers.isNotEmpty()) {
                            records.add(VCardRecord(finalName, numbers.toList()))
                        }
                    }
                    inCard = false
                }
                inCard -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val header = line.substring(0, colon)
                    val value = unescape(line.substring(colon + 1).trim())
                    val property = header.substringBefore(';').substringAfterLast('.').uppercase(Locale.ROOT)
                    when (property) {
                        "FN" -> if (value.isNotBlank()) name = value
                        "N" -> {
                            structuredName = value.split(';')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .joinToString(" ")
                        }
                        "TEL" -> {
                            val number = value.removePrefix("tel:")
                            if (number.isNotBlank()) numbers.add(number)
                        }
                    }
                }
            }
        }
        return records
    }

    private fun unfold(text: String): List<String> {
        val result = ArrayList<String>()
        val builder = StringBuilder()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if ((line.startsWith(" ") || line.startsWith("\t")) && builder.isNotEmpty()) {
                builder.append(line.substring(1))
            } else {
                if (builder.isNotEmpty()) result.add(builder.toString())
                builder.clear()
                builder.append(line)
            }
        }
        if (builder.isNotEmpty()) result.add(builder.toString())
        return result
    }

    private fun unescape(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }
}
