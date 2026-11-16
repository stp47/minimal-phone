package com.minimalphone.contacts

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minimalphone.calls.CallLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ContactsDestination {
    data object List : ContactsDestination
    data class Card(val id: Long) : ContactsDestination
    data class Form(val contactId: Long?, val initialName: String, val initialNumber: String) : ContactsDestination
}

class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ContactsRepository.get(application)

    private val _access = MutableStateFlow(false)
    val access: StateFlow<Boolean> = _access.asStateFlow()

    val contacts: StateFlow<List<Contact>> = repository.contacts

    private val _destination = MutableStateFlow<ContactsDestination>(ContactsDestination.List)
    val destination: StateFlow<ContactsDestination> = _destination.asStateFlow()

    val isRoot: StateFlow<Boolean> = _destination
        .map { it is ContactsDestination.List }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var hadAccess = false
    private var saving = false
    private var navToken = 0
    private var loadToken = 0
    private var loadedOnce = false

    init {
        refresh()
    }

    fun refresh() {
        val context = getApplication<Application>()
        val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val canWrite = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val granted = canRead && canWrite
        val becameGranted = granted && !hadAccess
        _access.value = granted
        hadAccess = granted
        if (!granted) {
            _loading.value = false
            if (!canRead) repository.invalidate()
            _destination.value = ContactsDestination.List
            return
        }
        val token = ++loadToken
        val showLoading = !loadedOnce || becameGranted
        if (showLoading) _loading.value = true
        viewModelScope.launch {
            try {
                val result = repository.ensureLoaded(force = becameGranted)
                if (result.isFailure) _messages.trySend("Ошибка")
            } finally {
                if (token == loadToken) {
                    _loading.value = false
                    loadedOnce = true
                }
            }
        }
    }

    fun openContact(id: Long) {
        if (!_access.value) return
        if (repository.byId(id) != null) {
            navToken++
            _destination.value = ContactsDestination.Card(id)
        }
    }

    fun openAdd(number: String = "") {
        if (!_access.value) return
        navToken++
        _destination.value = ContactsDestination.Form(null, "", number)
    }

    fun edit(contactId: Long) {
        val contact = repository.byId(contactId) ?: return
        navToken++
        _destination.value = ContactsDestination.Form(
            contactId = contact.id,
            initialName = if (contact.hasName) contact.name else "",
            initialNumber = contact.number,
        )
    }

    fun backToList() {
        navToken++
        _destination.value = ContactsDestination.List
    }

    fun save(contactId: Long?, name: String, number: String) {
        val trimmedName = name.trim()
        if (saving || trimmedName.isEmpty() || !com.minimalphone.ui.Formats.isValid(getApplication(), number)) return
        saving = true
        val token = navToken
        viewModelScope.launch {
            try {
                val result = if (contactId == null) {
                    repository.create(trimmedName, number)
                } else {
                    val contact = repository.byId(contactId)
                    if (contact == null) ContactWriteResult.WriteError else repository.update(contact, trimmedName, number)
                }
                when (result) {
                    ContactWriteResult.Success -> if (token == navToken) {
                        navToken++
                        _destination.value = ContactsDestination.List
                    }
                    ContactWriteResult.ReloadError -> {
                        _messages.trySend("Ошибка")
                        if (token == navToken) {
                            navToken++
                            _destination.value = ContactsDestination.List
                        }
                    }
                    ContactWriteResult.WriteError -> _messages.trySend("Ошибка")
                }
            } finally {
                saving = false
            }
        }
    }

    fun delete(contact: Contact) {
        val token = navToken
        viewModelScope.launch {
            when (repository.delete(contact)) {
                ContactWriteResult.Success -> if (token == navToken) {
                    navToken++
                    _destination.value = ContactsDestination.List
                }
                ContactWriteResult.ReloadError -> {
                    _messages.trySend("Ошибка")
                    if (token == navToken) {
                        navToken++
                        _destination.value = ContactsDestination.List
                    }
                }
                ContactWriteResult.WriteError -> _messages.trySend("Ошибка")
            }
        }
    }

    fun call(contact: Contact) {
        val placed = CallLauncher.place(getApplication(), contact.number)
        if (placed.isSuccess) {
            CallLauncher.openScreen(getApplication(), contact.number)
        } else {
            _messages.trySend("Ошибка")
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            when (repository.import(uri)) {
                ImportResult.Success -> _messages.trySend("Готово")
                ImportResult.NoContacts -> _messages.trySend("Нет контактов")
                ImportResult.Error -> _messages.trySend("Ошибка")
            }
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            if (repository.ensureLoaded().isFailure) {
                _messages.trySend("Ошибка")
                return@launch
            }
            when (repository.export(uri, repository.contacts.value)) {
                ExportResult.Success -> _messages.trySend("Готово")
                ExportResult.NoContacts -> _messages.trySend("Нет контактов")
                ExportResult.Error -> _messages.trySend("Ошибка")
            }
        }
    }

    fun noContacts() {
        _messages.trySend("Нет контактов")
    }

    fun openExternalContact(uri: Uri) {
        if (!_access.value) {
            navToken++
            _destination.value = ContactsDestination.List
            return
        }
        val token = ++navToken
        viewModelScope.launch {
            val loaded = repository.ensureLoaded()
            if (loaded.isFailure) {
                _messages.trySend("Ошибка")
                if (token == navToken) {
                    _destination.value = ContactsDestination.List
                }
                return@launch
            }
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
                ?: withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.query(
                            uri,
                            arrayOf(ContactsContract.Contacts._ID),
                            null,
                            null,
                            null,
                        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
                    }.getOrNull()
                }
            if (token != navToken) return@launch
            if (id != null && repository.byId(id) != null) {
                _destination.value = ContactsDestination.Card(id)
            } else {
                _destination.value = ContactsDestination.List
            }
        }
    }
}
