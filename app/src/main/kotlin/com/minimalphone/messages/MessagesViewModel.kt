package com.minimalphone.messages

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minimalphone.contacts.Contact
import com.minimalphone.contacts.ContactsRepository
import com.minimalphone.ui.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MessagesAccess {
    SMS,
    CONTACTS,
    READY,
}

data class ConversationUi(
    val address: String,
    val key: String,
    val name: String,
    val letter: String,
    val snippet: String,
    val date: Long,
)

class MessagesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmsRepository.get(application)
    private val contactsRepository = ContactsRepository.get(application)

    private val _access = MutableStateFlow(MessagesAccess.SMS)
    val access: StateFlow<MessagesAccess> = _access.asStateFlow()

    private val _chatAddress = MutableStateFlow<String?>(null)
    val chatAddress: StateFlow<String?> = _chatAddress.asStateFlow()

    private val _messages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val messages: StateFlow<List<SmsMessage>> = _messages.asStateFlow()

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    private val _composerOpen = MutableStateFlow(false)
    val composerOpen: StateFlow<Boolean> = _composerOpen.asStateFlow()

    private val _conversationsLoading = MutableStateFlow(false)
    val conversationsLoading: StateFlow<Boolean> = _conversationsLoading.asStateFlow()

    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    private var sending = false
    private var chatLoadToken = 0
    private var conversationsLoadToken = 0
    private var conversationsLoadedOnce = false

    val contacts: StateFlow<List<Contact>> = contactsRepository.contacts

    val conversations: StateFlow<List<ConversationUi>> =
        combine(repository.conversations, contactsRepository.contacts) { list, _ ->
            val region = Formats.region(getApplication<Application>())
            list.map { conversation ->
                val contact = contactsRepository.findByKey(conversation.address, region)
                ConversationUi(
                    address = conversation.address,
                    key = conversation.key,
                    name = contact?.name ?: Formats.format(conversation.address, region),
                    letter = contact?.name?.let { Formats.letter(it) }
                        ?: Formats.letter(conversation.address),
                    snippet = conversation.snippet,
                    date = conversation.date,
                )
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatName: StateFlow<String> =
        combine(_chatAddress, contactsRepository.contacts) { address, _ ->
            if (address == null) {
                ""
            } else {
                val region = Formats.region(getApplication<Application>())
                contactsRepository.findByKey(address, region)?.name ?: Formats.format(address, region)
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val isRoot: StateFlow<Boolean> =
        combine(_chatAddress, _sheetOpen) { chat, sheet -> chat == null && !sheet }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    init {
        refresh()
        viewModelScope.launch {
            SmsEvents.events.collect {
                val address = _chatAddress.value
                if (address != null) loadChat(address)
                repository.reloadConversations()
            }
        }
    }

    fun refresh() {
        val context = getApplication<Application>()
        val roleManager = context.getSystemService(RoleManager::class.java)
        val smsRole = roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        val readSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val receiveSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val sendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

        val next = when {
            !(smsRole && readSms && receiveSms && sendSms) -> MessagesAccess.SMS
            !contacts -> MessagesAccess.CONTACTS
            else -> MessagesAccess.READY
        }
        _access.value = next
        if (next != MessagesAccess.READY) {
            conversationsLoadToken++
            _chatAddress.value = null
            _sheetOpen.value = false
            _composerOpen.value = false
            _messages.value = emptyList()
            _conversationsLoading.value = false
            _chatLoading.value = false
            conversationsLoadedOnce = false
            return
        }
        val token = ++conversationsLoadToken
        val showLoading = !conversationsLoadedOnce
        if (showLoading) _conversationsLoading.value = true
        viewModelScope.launch {
            try {
                val loaded = contactsRepository.ensureLoaded()
                if (loaded.isFailure) _events.trySend("Ошибка")
                val result = repository.reloadConversations()
                if (result.isFailure) _events.trySend("Ошибка")
            } finally {
                if (token == conversationsLoadToken) {
                    _conversationsLoading.value = false
                    conversationsLoadedOnce = true
                }
            }
        }
    }

    fun openSheet() {
        if (_access.value != MessagesAccess.READY) return
        _sheetOpen.value = true
    }

    fun dismissSheet() {
        _sheetOpen.value = false
    }

    fun selectContact(contact: Contact) {
        _sheetOpen.value = false
        openChat(contact.number)
    }

    fun openExternalChat(address: String) {
        if (_access.value != MessagesAccess.READY) return
        openChat(address)
    }

    fun openChat(address: String) {
        if (_access.value != MessagesAccess.READY) return
        if (!isCurrent(address)) {
            _messages.value = emptyList()
            _chatLoading.value = true
        }
        _chatAddress.value = address
        _sheetOpen.value = false
        _composerOpen.value = false
        SmsNotifier.cancelForKey(getApplication(), address)
        loadChat(address)
    }

    fun closeChat() {
        _chatAddress.value = null
        _composerOpen.value = false
        _messages.value = emptyList()
        _chatLoading.value = false
    }

    fun showComposer() {
        _composerOpen.value = true
    }

    fun hideComposer() {
        _composerOpen.value = false
    }

    fun send(text: String) {
        val address = _chatAddress.value ?: return
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true
        viewModelScope.launch {
            try {
                val stored = repository.storeOutgoing(address, body)
                val message = stored.getOrNull() ?: return@launch
                if (isCurrent(address)) hideComposer()
                val sent = withContext(Dispatchers.IO) {
                    SmsSender.send(getApplication(), address, body, message.id, message.date)
                }
                if (sent.isFailure) {
                    repository.markFailed(message.id, address, message.date)
                    SmsEvents.changed(message.id)
                }
            } finally {
                sending = false
            }
            loadChat(address)
            repository.reloadConversations()
        }
    }

    fun deleteMessage(message: SmsMessage) {
        viewModelScope.launch {
            val result = repository.deleteMessage(message.id)
            if (result.isSuccess) {
                _chatAddress.value?.let { loadChat(it) }
                repository.reloadConversations()
            } else {
                _events.trySend("Ошибка")
            }
        }
    }

    fun deleteConversation(conversation: ConversationUi) {
        viewModelScope.launch {
            val result = repository.deleteConversation(conversation.address)
            if (result.isSuccess) {
                repository.reloadConversations()
            } else {
                _events.trySend("Ошибка")
            }
        }
    }

    private fun isCurrent(address: String): Boolean =
        _chatAddress.value?.let { repository.addressKey(it) == repository.addressKey(address) } == true

    private fun loadChat(address: String) {
        if (!isCurrent(address)) return
        val token = ++chatLoadToken
        viewModelScope.launch {
            val result = repository.loadMessages(address)
            if (token != chatLoadToken || !isCurrent(address)) return@launch
            _chatLoading.value = false
            result.fold(
                onSuccess = { _messages.value = it },
                onFailure = { _events.trySend("Ошибка") },
            )
        }
    }
}
