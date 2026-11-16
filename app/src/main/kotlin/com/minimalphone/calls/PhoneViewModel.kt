package com.minimalphone.calls

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minimalphone.ui.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min

data class CallGroup(
    val id: Long,
    val number: String,
    val name: String,
    val callable: Boolean,
    val entries: List<CallEntry>,
)

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val contacts = com.minimalphone.contacts.ContactsRepository.get(application)
    private val callLog = CallLogRepository.get(application)

    private val _access = MutableStateFlow(false)
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private val _raw = MutableStateFlow("")
    private val _display = MutableStateFlow("")
    val display: StateFlow<String> = _display.asStateFlow()
    val number: StateFlow<String> = _raw.asStateFlow()

    private val _recentsOpen = MutableStateFlow(false)
    val recentsOpen: StateFlow<Boolean> = _recentsOpen.asStateFlow()

    val isRoot: StateFlow<Boolean> = _recentsOpen
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _recents = MutableStateFlow<List<CallGroup>>(emptyList())
    val recents: StateFlow<List<CallGroup>> = _recents.asStateFlow()

    private val _expanded = MutableStateFlow<Long?>(null)
    val expanded: StateFlow<Long?> = _expanded.asStateFlow()

    private val _recentsLoading = MutableStateFlow(false)
    val recentsLoading: StateFlow<Boolean> = _recentsLoading.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val cleanupMutex = Mutex()
    private var labelToken = 0
    private var recentsLoadToken = 0
    private var wasAccess = false
    private var sawCall = false

    init {
        val granted = hasAccess()
        _access.value = granted
        wasAccess = granted
        if (granted) {
            SimSelection.reset(application)
            cleanup()
        }
        loadContactNames()
        viewModelScope.launch {
            contacts.contacts.collect {
                if (_recentsOpen.value) loadRecents()
            }
        }
        viewModelScope.launch {
            CallSession.state.collect { state ->
                if (state != null) {
                    sawCall = true
                } else if (sawCall) {
                    sawCall = false
                    cleanup()
                    if (_recentsOpen.value) loadRecents()
                }
            }
        }
    }

    fun refresh() {
        val granted = hasAccess()
        val becameGranted = granted && !wasAccess
        _access.value = granted
        wasAccess = granted
        if (!granted) {
            _recentsOpen.value = false
            _expanded.value = null
            _recentsLoading.value = false
            return
        }
        SimSelection.ensure(getApplication())
        if (becameGranted) cleanup()
        loadContactNames()
    }

    fun hasAccess(): Boolean {
        val context = getApplication<Application>()
        val role = context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        val callLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        val writeLog = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        val call = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val phoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        return role && callLog && writeLog && call && phoneState
    }

    fun prefill(number: String) {
        labelToken++
        _raw.value = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        updateDisplay()
    }

    fun press(key: Char) {
        labelToken++
        _raw.value = _raw.value + key
        updateDisplay()
    }

    fun longZero() {
        labelToken++
        val current = _raw.value
        _raw.value = when {
            current.isEmpty() -> "+"
            current.startsWith("+") -> current
            else -> "+" + current
        }
        updateDisplay()
    }

    fun backspace() {
        labelToken++
        if (_raw.value.isNotEmpty()) _raw.value = _raw.value.dropLast(1)
        updateDisplay()
    }

    fun clear() {
        labelToken++
        _raw.value = ""
        updateDisplay()
    }

    fun mainAction() {
        if (_raw.value.any { it.isDigit() }) {
            call(_raw.value)
        } else {
            openRecents()
        }
    }

    fun simClick() {
        if (!_access.value) return
        if (SimSelection.cycle(getApplication())) {
            val token = ++labelToken
            _display.value = SimSelection.label() ?: Formats.asYouType(getApplication(), _raw.value)
            viewModelScope.launch {
                delay(2000)
                if (token == labelToken) updateDisplay()
            }
        }
    }

    fun openRecents() {
        if (!_access.value) return
        _recentsOpen.value = true
        _expanded.value = null
        _recentsLoading.value = true
        cancelMissedNotifications()
        viewModelScope.launch { callLog.markMissedRead() }
        cleanup()
        loadRecents()
    }

    fun closeRecents() {
        _recentsOpen.value = false
        _expanded.value = null
        _recentsLoading.value = false
    }

    fun collapseGroups() {
        _expanded.value = null
    }

    fun toggleGroup(id: Long) {
        _expanded.value = if (_expanded.value == id) null else id
    }

    fun callGroup(group: CallGroup) {
        call(group.number)
    }

    fun deleteGroup(group: CallGroup) {
        viewModelScope.launch {
            val result = callLog.delete(group.entries.map { it.id })
            if (result.isSuccess) {
                loadRecents()
            } else {
                _messages.trySend("Ошибка")
            }
        }
    }

    fun callFromContact(number: String) {
        call(number)
    }

    private fun call(number: String) {
        val result = CallLauncher.place(getApplication(), number)
        if (result.isSuccess) {
            CallLauncher.openScreen(getApplication(), number)
        } else {
            _messages.trySend("Ошибка")
        }
    }

    private fun updateDisplay() {
        _display.value = Formats.asYouType(getApplication(), _raw.value)
    }

    private fun loadRecents() {
        val token = ++recentsLoadToken
        viewModelScope.launch {
            val result = callLog.load(100)
            if (token != recentsLoadToken) return@launch
            _recentsLoading.value = false
            result.fold(
                onSuccess = { entries -> _recents.value = group(entries) },
                onFailure = { _messages.trySend("Ошибка") },
            )
        }
    }

    private suspend fun group(entries: List<CallEntry>): List<CallGroup> = withContext(Dispatchers.Default) {
        val region = Formats.region(getApplication())
        val groups = ArrayList<CallGroup>()
        entries.forEach { entry ->
            val label = presentationLabel(entry.presentation)
            val callable = label == null && entry.number.isNotBlank() &&
                !entry.number.startsWith("-")
            val name = label ?: if (callable) {
                contacts.findByKey(entry.number, region)?.name
                    ?: Formats.format(entry.number, region)
            } else {
                "Неизвестный номер"
            }
            val key = Formats.canonicalKey(entry.number, region)
            val last = groups.lastOrNull()
            if (last != null && Formats.canonicalKey(last.number, region) == key) {
                groups[groups.lastIndex] = last.copy(entries = last.entries + entry)
            } else {
                groups.add(
                    CallGroup(
                        id = entry.id,
                        number = entry.number,
                        name = name,
                        callable = callable,
                        entries = listOf(entry),
                    ),
                )
            }
        }
        groups
    }

    private fun loadContactNames() {
        val context = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        viewModelScope.launch {
            val result = contacts.ensureLoaded()
            if (result.isFailure) _messages.trySend("Ошибка")
        }
    }

    private fun cleanup() {
        if (!_access.value) return
        viewModelScope.launch {
            cleanupMutex.withLock {
                val result = callLog.cleanup(100)
                if (result.isFailure) _messages.trySend("Ошибка")
            }
        }
    }

    private fun cancelMissedNotifications() {
        val manager = getApplication<Application>().getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.tag == MissedCallReceiver.TAG_MISSED }
            .forEach { manager.cancel(it.tag, it.id) }
    }
}
