package com.minimalphone.contacts

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimalphone.ui.AccessMessage
import com.minimalphone.ui.AppFab
import com.minimalphone.ui.AppHeader
import com.minimalphone.ui.AppSmallFab
import com.minimalphone.ui.AppTitle
import com.minimalphone.ui.Avatar
import com.minimalphone.ui.appTextFieldColors
import com.minimalphone.ui.Dimens
import com.minimalphone.ui.EmptyState
import com.minimalphone.ui.Formats
import com.minimalphone.ui.LoadingState
import com.minimalphone.ui.SwipeActionBackground
import com.minimalphone.ui.blockPagerSwipe
import com.minimalphone.ui.tabular
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ContactsPage(
    vm: ContactsViewModel,
    listState: LazyListState,
    phoneAccess: Boolean,
    messagesReady: Boolean,
    onSwitchToPhone: () -> Unit,
    onSwitchToMessages: () -> Unit,
    onMessageContact: (String) -> Unit,
) {
    val access by vm.access.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<Contact?>(null) }
    var fabMenuOpen by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(destination, access) {
        fabMenuOpen = false
        deleteTarget = null
    }

    BackHandler(enabled = destination !is ContactsDestination.List) {
        vm.backToList()
        fabMenuOpen = false
    }
    BackHandler(enabled = fabMenuOpen) { fabMenuOpen = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        when {
            !access -> Column(modifier = Modifier.fillMaxSize()) {
                AppTitle("Контакты")
                Box(modifier = Modifier.weight(1f)) {
                    AccessMessage("Необходимо разрешение: контакты")
                }
            }
            destination is ContactsDestination.List -> ContactsList(
                contacts = contacts,
                listState = listState,
                loading = loading,
                fabMenuOpen = fabMenuOpen,
                onFabMenuChange = { fabMenuOpen = it },
                onContactClick = vm::openContact,
                onCall = { contact ->
                    if (phoneAccess) vm.call(contact) else onSwitchToPhone()
                },
                onDeleteRequest = { deleteTarget = it },
                onAdd = {
                    fabMenuOpen = false
                    vm.openAdd()
                },
                onExport = {
                    fabMenuOpen = false
                    if (contacts.isEmpty()) {
                        vm.noContacts()
                    } else {
                        val name = "contacts-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".vcf"
                        exportLauncher.launch(name)
                    }
                },
                onImport = {
                    fabMenuOpen = false
                    importLauncher.launch(arrayOf("text/x-vcard", "text/vcard"))
                },
            )
            destination is ContactsDestination.Card -> {
                val id = (destination as ContactsDestination.Card).id
                val contact = contacts.firstOrNull { it.id == id }
                if (contact == null) {
                    LaunchedEffect(id) { vm.backToList() }
                } else {
                    ContactCard(
                        contact = contact,
                        phoneAccess = phoneAccess,
                        messagesReady = messagesReady,
                        onBack = vm::backToList,
                        onCall = { if (phoneAccess) vm.call(contact) else onSwitchToPhone() },
                        onMessage = {
                            if (messagesReady) onMessageContact(contact.number) else onSwitchToMessages()
                        },
                        onEdit = { vm.edit(contact.id) },
                        onDelete = { vm.delete(contact) },
                    )
                }
            }
            destination is ContactsDestination.Form -> {
                val form = destination as ContactsDestination.Form
                key(form.contactId, form.initialName, form.initialNumber) {
                    ContactForm(
                        title = if (form.contactId == null) "Новый контакт" else "Изменить контакт",
                        initialName = form.initialName,
                        initialNumber = form.initialNumber,
                        onBack = vm::backToList,
                        onSave = { name, number -> vm.save(form.contactId, name, number) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (access && destination is ContactsDestination.List) Dimens.SnackbarBottom else Dimens.SnackbarBottomNone),
        )
    }

    deleteTarget?.let { contact ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить контакт?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        vm.delete(contact)
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ContactsList(
    contacts: List<Contact>,
    listState: LazyListState,
    loading: Boolean,
    fabMenuOpen: Boolean,
    onFabMenuChange: (Boolean) -> Unit,
    onContactClick: (Long) -> Unit,
    onCall: (Contact) -> Unit,
    onDeleteRequest: (Contact) -> Unit,
    onAdd: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val groups = remember(contacts) {
        val map = LinkedHashMap<String, MutableList<Contact>>()
        contacts.forEach { contact ->
            val first = contact.name.trim().firstOrNull()
            val letter = if (first != null && first.isLetter()) first.lowercaseChar().toString() else "#"
            map.getOrPut(letter) { ArrayList() }.add(contact)
        }
        val ordered = map.entries.sortedBy { it.key == "#" }.associate { it.key to it.value }
        ordered
    }

    val backgroundSemantics = if (fabMenuOpen) Modifier.clearAndSetSemantics { } else Modifier
    Box(modifier = Modifier.fillMaxSize()) {
        AppTitle(
            "Контакты",
            Modifier.align(Alignment.TopCenter).then(backgroundSemantics),
        )
        if (contacts.isEmpty()) {
            if (loading) {
                LoadingState(
                    Modifier.fillMaxSize().padding(top = Dimens.TopBarHeight).then(backgroundSemantics),
                )
            } else {
                EmptyState(
                    "Нет контактов",
                    Modifier.fillMaxSize().padding(top = Dimens.TopBarHeight).then(backgroundSemantics),
                    Icons.Outlined.Person,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(top = Dimens.TopBarHeight).then(backgroundSemantics),
                contentPadding = PaddingValues(bottom = Dimens.ListBottomPadding),
            ) {
                groups.forEach { (letter, list) ->
                    stickyHeader(key = "header-$letter") {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = Dimens.ListRowHorizontal, vertical = 8.dp)
                                .semantics { heading() },
                        )
                    }
                    items(items = list, key = { it.id }) { contact ->
                        ContactRow(
                            contact = contact,
                            onClick = { onContactClick(contact.id) },
                            onCall = { onCall(contact) },
                            onDelete = { onDeleteRequest(contact) },
                            onCopy = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(ClipData.newPlainText("", contact.number))
                            },
                        )
                    }
                }
            }
        }

        if (fabMenuOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blockPagerSwipe()
                    .pointerInput(Unit) {
                        detectTapGestures { onFabMenuChange(false) }
                    },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(visible = fabMenuOpen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!(loading && contacts.isEmpty())) {
                        AppSmallFab(onClick = onExport) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Экспорт")
                        }
                        Spacer(modifier = Modifier.height(Dimens.SpacingMedium))
                    }
                    AppSmallFab(onClick = onImport) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Импорт")
                    }
                    Spacer(modifier = Modifier.height(Dimens.SpacingMedium))
                }
            }
            AppFab(
                onClick = {
                    if (fabMenuOpen) onFabMenuChange(false) else onAdd()
                },
                onLongClick = { onFabMenuChange(!fabMenuOpen) },
                onLongClickLabel = "Экспорт и импорт",
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onCall()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                else -> Unit
            }
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            SwipeActionBackground(
                direction = dismissState.dismissDirection,
                startIcon = Icons.Filled.Call,
                endIcon = Icons.Filled.Delete,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onCopy,
                    onLongClickLabel = "Копировать номер",
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Позвонить") { onCall(); true },
                        CustomAccessibilityAction("Удалить") { onDelete(); true },
                    )
                }
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

@Composable
private fun ContactCard(
    contact: Contact,
    phoneAccess: Boolean,
    messagesReady: Boolean,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var confirmDelete by remember(contact.id) { mutableStateOf(false) }
    LaunchedEffect(contact.id) { scrollState.scrollTo(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(onBack = onBack, title = contact.name)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = Dimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Dimens.SpacingSmall))
            Avatar(letter = Formats.letter(contact.name), size = Dimens.AvatarCard)
            Spacer(modifier = Modifier.height(Dimens.ListRowGap))
            Text(
                text = Formats.format(context, contact.number),
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(40.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpacingMedium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TonalAction(
                    text = "Позвонить",
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                    onClick = onCall,
                )
                TonalAction(
                    text = "Написать",
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
                    onClick = onMessage,
                )
            }
            Spacer(modifier = Modifier.height(Dimens.SpacingMedium))
            TonalAction(
                text = "Изменить",
                modifier = Modifier.fillMaxWidth(),
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = onEdit,
            )
            Spacer(modifier = Modifier.height(Dimens.SpacingMedium))
            FilledTonalButton(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonHeight),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(Dimens.SpacingSmall))
                Text("Удалить")
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить контакт?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun TonalAction(
    text: String,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.ButtonHeight),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = Dimens.SpacingMedium),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        icon()
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun ContactForm(
    title: String,
    initialName: String,
    initialNumber: String,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val region = remember { Formats.region(context) }
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var rawNumber by rememberSaveable(initialNumber) { mutableStateOf(Formats.digitsOnly(initialNumber)) }
    var displayNumber by rememberSaveable(initialNumber, region) {
        mutableStateOf(Formats.asYouType(Formats.digitsOnly(initialNumber), region))
    }
    var nameTouched by rememberSaveable(initialName) { mutableStateOf(false) }
    var numberTouched by rememberSaveable(initialNumber) { mutableStateOf(false) }

    val nameValid = name.trim().isNotEmpty()
    val numberValid = remember(rawNumber, region) { Formats.isValid(rawNumber, region) }
    val valid = nameValid && numberValid
    val fieldColors = appTextFieldColors()

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(onBack = onBack, title = title)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            Spacer(modifier = Modifier.height(Dimens.SpacingLarge))
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    nameTouched = true
                },
                label = { Text("Имя") },
                singleLine = true,
                isError = nameTouched && !nameValid,
                supportingText = {
                    Text(if (nameTouched && !nameValid) "Введите имя" else " ")
                },
                shape = MaterialTheme.shapes.large,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Dimens.SpacingMedium))
            TextField(
                value = displayNumber,
                onValueChange = { value ->
                    val digits = Formats.digitsOnly(value)
                    rawNumber = digits
                    displayNumber = Formats.asYouType(digits, region)
                    numberTouched = true
                },
                label = { Text("Номер") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.tabular(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = numberTouched && !numberValid,
                supportingText = {
                    Text(if (numberTouched && !numberValid) "Неверный номер" else " ")
                },
                shape = MaterialTheme.shapes.large,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Dimens.SpacingLarge))
            Button(
                onClick = { onSave(name, rawNumber) },
                enabled = valid,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonHeight),
                shape = CircleShape,
            ) {
                Text("Сохранить")
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
