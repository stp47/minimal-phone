package com.minimalphone.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimalphone.AppState
import com.minimalphone.contacts.Contact
import com.minimalphone.ui.AccessMessage
import com.minimalphone.ui.AppFab
import com.minimalphone.ui.AppHeader
import com.minimalphone.ui.AppPrimaryIconButton
import com.minimalphone.ui.AppTitle
import com.minimalphone.ui.Avatar
import com.minimalphone.ui.Dimens
import com.minimalphone.ui.EmptyState
import com.minimalphone.ui.Formats
import com.minimalphone.ui.LoadingState
import com.minimalphone.ui.appTextFieldColors
import com.minimalphone.ui.SwipeActionBackground
import com.minimalphone.ui.blockPagerSwipe
import com.minimalphone.ui.tabular
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesPage(vm: MessagesViewModel, conversationsListState: androidx.compose.foundation.lazy.LazyListState) {
    val access by vm.access.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val chatAddress by vm.chatAddress.collectAsStateWithLifecycle()
    val chatName by vm.chatName.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sheetOpen by vm.sheetOpen.collectAsStateWithLifecycle()
    val composerOpen by vm.composerOpen.collectAsStateWithLifecycle()
    val conversationsLoading by vm.conversationsLoading.collectAsStateWithLifecycle()
    val chatLoading by vm.chatLoading.collectAsStateWithLifecycle()
    val chatVisible = access == MessagesAccess.READY && chatAddress != null
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<ConversationUi?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(chatAddress, access) {
        if (chatAddress != null || access != MessagesAccess.READY) deleteTarget = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        when (access) {
            MessagesAccess.SMS -> Column(modifier = Modifier.fillMaxSize()) {
                AppTitle("Сообщения")
                Box(modifier = Modifier.weight(1f)) {
                    AccessMessage("Необходимо разрешение: SMS")
                }
            }
            MessagesAccess.CONTACTS -> Column(modifier = Modifier.fillMaxSize()) {
                AppTitle("Сообщения")
                Box(modifier = Modifier.weight(1f)) {
                    AccessMessage("Необходимо разрешение: контакты")
                }
            }
            MessagesAccess.READY -> {
                if (chatAddress == null) {
                    ConversationList(
                        conversations = conversations,
                        listState = conversationsListState,
                        loading = conversationsLoading,
                        onOpen = vm::openChat,
                        onDeleteRequest = { deleteTarget = it },
                        onNewChat = vm::openSheet,
                    )
                } else {
                    ChatScreen(
                        address = chatAddress ?: "",
                        name = chatName.ifBlank { chatAddress ?: "" },
                        messages = messages,
                        composerOpen = composerOpen,
                        loading = chatLoading,
                        snackbar = snackbar,
                        onBack = vm::closeChat,
                        onShowComposer = vm::showComposer,
                        onHideComposer = vm::hideComposer,
                        onSend = vm::send,
                        onDeleteMessage = vm::deleteMessage,
                    )
                }
            }
        }
        if (!chatVisible) {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (access == MessagesAccess.READY) Dimens.SnackbarBottom else Dimens.SnackbarBottomNone),
            )
        }
    }

    if (sheetOpen && access == MessagesAccess.READY && chatAddress == null) {
        NewChatSheet(
            contacts = contacts,
            onSelect = vm::selectContact,
            onDismiss = vm::dismissSheet,
        )
    }

    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить диалог?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        vm.deleteConversation(conversation)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationList(
    conversations: List<ConversationUi>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onDeleteRequest: (ConversationUi) -> Unit,
    onNewChat: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AppTitle("Сообщения", Modifier.align(Alignment.TopCenter))
        if (conversations.isEmpty()) {
            if (loading) {
                LoadingState(Modifier.fillMaxSize().padding(top = Dimens.TopBarHeight))
            } else {
                EmptyState(
                    "Нет сообщений",
                    Modifier.fillMaxSize().padding(top = Dimens.TopBarHeight),
                    Icons.Outlined.ChatBubbleOutline,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(top = Dimens.TopBarHeight),
                contentPadding = PaddingValues(bottom = Dimens.ListBottomPadding),
            ) {
                items(items = conversations, key = { it.key }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onClick = { onOpen(conversation.address) },
                        onDelete = { onDeleteRequest(conversation) },
                    )
                }
            }
        }
        AppFab(
            onClick = onNewChat,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Новый чат")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(
    conversation: ConversationUi,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            SwipeActionBackground(
                direction = dismissState.dismissDirection,
                endIcon = Icons.Filled.Delete,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Удалить") { onDelete(); true },
                    )
                }
                .padding(horizontal = Dimens.ListRowHorizontal, vertical = Dimens.ListRowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(letter = conversation.letter, size = Dimens.AvatarList)
            Spacer(modifier = Modifier.width(Dimens.ListRowGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = conversation.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = Formats.listTime(conversation.date),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    contacts: List<Contact>,
    onSelect: (Contact) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rowHeight = Dimens.AvatarList + Dimens.ListRowVertical * 2
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BoxWithConstraints {
            val listHeight = (rowHeight * contacts.size).coerceAtMost(maxHeight * 0.6f)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Выберите контакт",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpacingMedium)
                        .semantics { heading() },
                )
                if (contacts.isEmpty()) {
                    EmptyState(
                        "Нет контактов",
                        Modifier
                            .fillMaxWidth()
                            .height(Dimens.PickerEmptyHeight),
                        Icons.Outlined.Person,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(listHeight),
                    ) {
                        items(items = contacts, key = { it.id }) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(contact) }
                                    .padding(horizontal = Dimens.ListRowHorizontal, vertical = Dimens.ListRowVertical),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(letter = Formats.letter(contact.name), size = Dimens.AvatarList)
                                Spacer(modifier = Modifier.width(Dimens.ListRowGap))
                                Text(
                                    text = contact.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatScreen(
    address: String,
    name: String,
    messages: List<SmsMessage>,
    composerOpen: Boolean,
    loading: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onShowComposer: () -> Unit,
    onHideComposer: () -> Unit,
    onSend: (String) -> Unit,
    onDeleteMessage: (SmsMessage) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var text by remember(address) { mutableStateOf("") }
    var initialScrollDone by remember(address) { mutableStateOf(false) }
    var lastCount by remember(address) { mutableIntStateOf(messages.size) }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    val closeComposer: () -> Unit = {
        onHideComposer()
        text = ""
        keyboard?.hide()
    }

    DisposableEffect(address) {
        AppState.openChatAddress = address
        onDispose {
            if (AppState.openChatAddress == address) AppState.openChatAddress = null
        }
    }

    LaunchedEffect(address, messages.size) {
        if (messages.isEmpty()) {
            lastCount = 0
            return@LaunchedEffect
        }
        val info = listState.layoutInfo
        val nearBottom = info.visibleItemsInfo.lastOrNull()?.index?.let { last ->
            last >= info.totalItemsCount - 2
        } ?: false
        val outgoing = messages.lastOrNull()?.incoming == false
        val grew = messages.size > lastCount
        if (!initialScrollDone || (grew && outgoing) || (grew && nearBottom)) {
            listState.scrollToItem(messages.lastIndex)
            initialScrollDone = true
        }
        lastCount = messages.size
    }

    LaunchedEffect(composerOpen) {
        if (composerOpen) {
            focusRequester.requestFocus()
            keyboard?.show()
            if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        } else {
            text = ""
            keyboard?.hide()
            imeWasVisible = false
        }
    }

    LaunchedEffect(imeVisible, composerOpen) {
        if (composerOpen && imeVisible) {
            imeWasVisible = true
            if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
        } else if (composerOpen && !imeVisible && imeWasVisible) {
            closeComposer()
            imeWasVisible = false
        }
    }

    BackHandler {
        if (composerOpen) closeComposer() else onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        AppHeader(
            onBack = { if (composerOpen) closeComposer() else onBack() },
            title = name,
        )

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                if (loading) {
                    LoadingState(Modifier.fillMaxSize())
                } else {
                    EmptyState("Нет сообщений", Modifier.fillMaxSize(), Icons.Outlined.ChatBubbleOutline)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.ScreenPadding,
                        top = 8.dp,
                        end = Dimens.ScreenPadding,
                        bottom = if (composerOpen) 8.dp else Dimens.SnackbarBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            onDelete = { onDeleteMessage(message) },
                            onShowTime = {
                                scope.launch { snackbar.showSnackbar(Formats.fullTime(message.date)) }
                            },
                        )
                    }
                }
            }
            if (!composerOpen) {
                AppPrimaryIconButton(
                    onClick = onShowComposer,
                    contentDescription = "Написать",
                    icon = Icons.Filled.Add,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (composerOpen) Dimens.SnackbarBottomNone else Dimens.SnackbarBottom),
            )
        }

        if (composerOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    maxLines = 4,
                    placeholder = { Text("Сообщение") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    shape = MaterialTheme.shapes.large,
                    colors = appTextFieldColors(),
                )
                Spacer(modifier = Modifier.width(Dimens.SpacingSmall))
                AppPrimaryIconButton(
                    onClick = { if (text.isNotBlank()) onSend(text) },
                    contentDescription = "Отправить",
                    icon = Icons.AutoMirrored.Filled.Send,
                    enabled = text.isNotBlank(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageRow(
    message: SmsMessage,
    onDelete: () -> Unit,
    onShowTime: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val bubbleShape = if (message.incoming) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            SwipeActionBackground(
                direction = dismissState.dismissDirection,
                endIcon = Icons.Filled.Delete,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 2.dp),
            horizontalArrangement = if (message.incoming) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = Dimens.ChatBubbleMaxWidth)
                    .clip(bubbleShape)
                    .background(
                        if (message.incoming) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.primaryContainer,
                        bubbleShape,
                    )
                    .clearAndSetSemantics {
                        contentDescription = if (message.incoming) message.body else "Вы: ${message.body}"
                        onLongClick(label = "Показать время") {
                            onShowTime()
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction("Показать время") { onShowTime(); true },
                            CustomAccessibilityAction("Удалить") { onDelete(); true },
                        )
                    }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = {},
                        onLongClick = onShowTime,
                        onLongClickLabel = "Показать время",
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.incoming) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (!message.incoming && message.failed) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Ошибка отправки",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
