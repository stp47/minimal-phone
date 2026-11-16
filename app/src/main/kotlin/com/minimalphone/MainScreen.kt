package com.minimalphone

import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minimalphone.calls.PhonePage
import com.minimalphone.calls.PhoneViewModel
import com.minimalphone.contacts.ContactsPage
import com.minimalphone.contacts.ContactsViewModel
import com.minimalphone.messages.MessagesAccess
import com.minimalphone.messages.MessagesPage
import com.minimalphone.messages.MessagesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun initialPage(intent: Intent?): Int = when {
    intent == null -> 1
    intent.action == MainActivity.ACTION_OPEN_CHAT -> 2
    intent.action == MainActivity.ACTION_OPEN_RECENTS -> 1
    intent.action == Intent.ACTION_INSERT -> 0
    intent.action == Intent.ACTION_VIEW && intent.type == "vnd.android.cursor.item/contact" -> 0
    (intent.action == Intent.ACTION_SENDTO || intent.action == Intent.ACTION_VIEW) &&
        intent.data?.scheme in setOf("sms", "smsto") -> 2
    else -> 1
}

@Composable
fun MainScreen(intents: StateFlow<MainIntent>, initialPage: Int = 1) {
    val contactsVm: ContactsViewModel = viewModel()
    val phoneVm: PhoneViewModel = viewModel()
    val messagesVm: MessagesViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialPage) { 3 }
    val contactsListState = rememberLazyListState()
    val conversationsListState = rememberLazyListState()

    val contactsRoot by contactsVm.isRoot.collectAsStateWithLifecycle()
    val phoneRoot by phoneVm.isRoot.collectAsStateWithLifecycle()
    val messagesRoot by messagesVm.isRoot.collectAsStateWithLifecycle()
    val phoneAccess by phoneVm.access.collectAsStateWithLifecycle()
    val messagesAccess by messagesVm.access.collectAsStateWithLifecycle()
    val intentEvent by intents.collectAsStateWithLifecycle()

    LaunchedEffect(intentEvent.id) {
        val current = intentEvent.intent ?: return@LaunchedEffect
        val action = current.action
        val data = current.data
        val contactMime = if (
            action == Intent.ACTION_VIEW && current.type == null && data?.scheme == "content"
        ) {
            withContext(Dispatchers.IO) {
                data.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            }
        } else {
            null
        }
        when {
            action == Intent.ACTION_MAIN -> {
                phoneVm.closeRecents()
                pagerState.animateScrollToPage(1)
            }
            action == MainActivity.ACTION_OPEN_RECENTS -> {
                phoneVm.openRecents()
                pagerState.animateScrollToPage(1)
            }
            action == MainActivity.ACTION_OPEN_CHAT -> {
                val address = runCatching {
                    current.getCharSequenceExtra(MainActivity.EXTRA_CHAT_ADDRESS)
                }.getOrNull()?.toString()
                if (!address.isNullOrBlank()) {
                    messagesVm.openExternalChat(address)
                    pagerState.animateScrollToPage(2)
                }
            }
            action == Intent.ACTION_DIAL || (action == Intent.ACTION_VIEW && data?.scheme == "tel") -> {
                phoneVm.closeRecents()
                val number = data?.schemeSpecificPart.orEmpty()
                    .removePrefix("//")
                    .substringBefore('?')
                    .substringBefore(';')
                if (number.isNotBlank()) phoneVm.prefill(number)
                pagerState.animateScrollToPage(1)
            }
            (action == Intent.ACTION_SENDTO || action == Intent.ACTION_VIEW) &&
                data?.scheme in setOf("sms", "smsto") -> {
                val address = data?.schemeSpecificPart
                    ?.removePrefix("//")
                    ?.substringBefore('?')
                    ?.split(',', ';')
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (address.isNotEmpty()) messagesVm.openExternalChat(address)
                pagerState.animateScrollToPage(2)
            }
            action == Intent.ACTION_INSERT -> {
                val number = runCatching {
                    current.getCharSequenceExtra(ContactsContract.Intents.Insert.PHONE)
                }.getOrNull()?.toString().orEmpty()
                contactsVm.openAdd(number)
                pagerState.animateScrollToPage(0)
            }
            action == Intent.ACTION_VIEW && (
                current.type == "vnd.android.cursor.item/contact" ||
                    (current.type == null && contactMime == "vnd.android.cursor.item/contact")
                ) -> {
                data?.let { contactsVm.openExternalContact(it) }
                pagerState.animateScrollToPage(0)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                contactsVm.refresh()
                phoneVm.refresh()
                messagesVm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentNested = when (pagerState.currentPage) {
        0 -> !contactsRoot
        1 -> !phoneRoot
        else -> !messagesRoot
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = !currentNested,
    ) { page ->
        when (page) {
            0 -> ContactsPage(
                vm = contactsVm,
                listState = contactsListState,
                phoneAccess = phoneAccess,
                messagesReady = messagesAccess == MessagesAccess.READY,
                onSwitchToPhone = {
                    contactsVm.backToList()
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                onSwitchToMessages = {
                    contactsVm.backToList()
                    scope.launch { pagerState.animateScrollToPage(2) }
                },
                onMessageContact = { number ->
                    contactsVm.backToList()
                    messagesVm.openExternalChat(number)
                    scope.launch { pagerState.animateScrollToPage(2) }
                },
            )
            1 -> PhonePage(phoneVm)
            else -> MessagesPage(messagesVm, conversationsListState)
        }
    }
}
