package com.minimalphone.calls

import android.content.ClipData
import android.content.ClipboardManager
import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minimalphone.ui.AccessMessage
import com.minimalphone.ui.AppHeader
import com.minimalphone.ui.AppPrimaryIconButton
import com.minimalphone.ui.AppTitle
import com.minimalphone.ui.Dimens
import com.minimalphone.ui.EmptyState
import com.minimalphone.ui.Formats
import com.minimalphone.ui.LoadingState
import com.minimalphone.ui.SwipeActionBackground
import com.minimalphone.ui.blockPagerSwipe
import com.minimalphone.ui.tabular
import kotlinx.coroutines.launch

@Composable
fun PhonePage(vm: PhoneViewModel) {
    val access by vm.access.collectAsStateWithLifecycle()
    val display by vm.display.collectAsStateWithLifecycle()
    val number by vm.number.collectAsStateWithLifecycle()
    val recentsOpen by vm.recentsOpen.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val recentsLoading by vm.recentsLoading.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val simAccounts by SimSelection.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.collapseGroups()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    BackHandler(enabled = recentsOpen) { vm.closeRecents() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        when {
            !access -> Column(modifier = Modifier.fillMaxSize()) {
                AppTitle("Телефон")
                Box(modifier = Modifier.weight(1f)) {
                    AccessMessage("Необходимо разрешение: телефон")
                }
            }
            recentsOpen -> {
                DisposableEffect(Unit) { onDispose { vm.collapseGroups() } }
                RecentsScreen(
                    groups = recents,
                    expanded = expanded,
                    loading = recentsLoading,
                    onBack = vm::closeRecents,
                    onToggle = vm::toggleGroup,
                    onCall = vm::callGroup,
                    onDelete = vm::deleteGroup,
                    onCopy = { numberToCopy ->
                        if (numberToCopy.isNotBlank()) {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("", numberToCopy))
                        }
                    },
                )
            }
            else -> DialpadScreen(
                display = display,
                hasNumber = number.any { it.isDigit() },
                showSim = simAccounts.size > 1,
                onDigit = vm::press,
                onLongZero = vm::longZero,
                onBackspace = vm::backspace,
                onClear = vm::clear,
                onMain = vm::mainAction,
                onSim = vm::simClick,
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = when {
                        !access -> Dimens.SnackbarBottomNone
                        recentsOpen -> Dimens.SnackbarBottomNone
                        else -> Dimens.SnackbarBottomPhone
                    },
                ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialpadScreen(
    display: String,
    hasNumber: Boolean,
    showSim: Boolean,
    onDigit: (Char) -> Unit,
    onLongZero: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onMain: () -> Unit,
    onSim: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val displayScale = if (fontScale > 1f) 1f / fontScale else 1f
    val displayStyle = MaterialTheme.typography.headlineLarge.copy(
        fontSize = (32f * displayScale).sp,
        lineHeight = (40f * displayScale).sp,
    ).tabular()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Dimens.ScreenPadding),
    ) {
        val compact = maxHeight < 560.dp
        val displayHeight = if (compact) 72.dp else 96.dp
        val keyGap = if (compact) 6.dp else 8.dp
        val bottomHeight = if (compact) 72.dp else 88.dp
        val bottomGap = if (compact) 8.dp else 16.dp
        val keySize = if (compact) {
            val available = maxHeight - displayHeight - keyGap * 5f - bottomHeight - bottomGap
            (available.value / 4f).dp.coerceIn(48.dp, 80.dp)
        } else {
            80.dp
        }
        val callSize = if (compact) 64.dp else 88.dp
        val callIconSize = if (compact) 28.dp else 36.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(displayHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = display,
                style = displayStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(keyGap))
        Spacer(modifier = Modifier.weight(1f))
        val rows = listOf(
            listOf('1', '2', '3'),
            listOf('4', '5', '6'),
            listOf('7', '8', '9'),
            listOf('*', '0', '#'),
        )
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                row.forEach { key ->
                    DialKey(
                        key = key,
                        keySize = keySize,
                        onClick = { onDigit(key) },
                        onLongClick = if (key == '0') onLongZero else null,
                        onLongClickLabel = if (key == '0') "Вставить +" else null,
                    )
                }
            }
            Spacer(modifier = Modifier.height(keyGap))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .size(Dimens.IconButton)
                        .clip(CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = onBackspace,
                            onLongClick = onClear,
                            onLongClickLabel = "Очистить номер",
                        )
                        .blockPagerSwipe(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Удалить")
                }
            }
            AppPrimaryIconButton(
                onClick = onMain,
                contentDescription = if (hasNumber) "Позвонить" else "История",
                icon = if (hasNumber) Icons.Filled.Call else Icons.Filled.History,
                size = callSize,
                iconSize = callIconSize,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (showSim) {
                    Box(
                        modifier = Modifier
                            .size(Dimens.IconButton)
                            .clip(CircleShape)
                            .combinedClickable(
                                role = Role.Button,
                                onClickLabel = "Сменить SIM",
                                onClick = onSim,
                            )
                            .blockPagerSwipe(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.SimCard,
                            contentDescription = SimSelection.label() ?: "SIM",
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(bottomGap))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    key: Char,
    keySize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
) {
    val isSpecial = key == '*' || key == '#'
    val fontScale = LocalDensity.current.fontScale
    val scale = if (fontScale > 1.3f) 1.3f / fontScale else 1f
    val baseSize = if (keySize < 72.dp) 24f else 32f
    val specialSize = if (keySize < 72.dp) 20f else 24f
    val subSize = if (keySize < 72.dp) 10f else 12f
    Box(
        modifier = Modifier
            .size(keySize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            )
            .blockPagerSwipe(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.toString(),
                color = if (isSpecial) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontSize = ((if (isSpecial) specialSize else baseSize) * scale).sp,
            )
            if (key == '0') {
                Text(
                    text = "+",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (subSize * scale).sp,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentsScreen(
    groups: List<CallGroup>,
    expanded: Long?,
    loading: Boolean,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onCall: (CallGroup) -> Unit,
    onDelete: (CallGroup) -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(onBack = onBack, title = "Недавние")
        if (groups.isEmpty()) {
            if (loading) {
                LoadingState(Modifier.weight(1f).fillMaxWidth())
            } else {
                EmptyState(
                    "Нет недавних звонков",
                    Modifier.weight(1f).fillMaxWidth(),
                    Icons.Outlined.History,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = Dimens.RecentsBottomPadding),
            ) {
                items(items = groups, key = { it.id }) { group ->
                    RecentGroup(
                        group = group,
                        expanded = expanded == group.id,
                        onToggle = { onToggle(group.id) },
                        onCall = { onCall(group) },
                        onDelete = { onDelete(group) },
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentGroup(
    group: CallGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = group.callable,
        enableDismissFromEndToStart = true,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> if (group.callable) onCall()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                else -> Unit
            }
            scope.launch { dismissState.reset() }
        },
        backgroundContent = {
            SwipeActionBackground(
                direction = dismissState.dismissDirection,
                startIcon = if (group.callable) Icons.Filled.Call else null,
                endIcon = Icons.Filled.Delete,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = indication,
                        onClick = {
                            if (group.entries.size > 1) onToggle()
                            else if (group.callable) onCall()
                        },
                        onLongClick = { onCopy(group.number) },
                        onLongClickLabel = "Копировать номер",
                    )
                    .semantics {
                        if (group.entries.size > 1) {
                            stateDescription = if (expanded) "Раскрыто" else "Свернуто"
                        }
                        customActions = buildList {
                            if (group.callable) {
                                add(CustomAccessibilityAction("Позвонить") { onCall(); true })
                            }
                            add(CustomAccessibilityAction("Удалить") { onDelete(); true })
                        }
                    }
                    .padding(horizontal = Dimens.ListRowHorizontal, vertical = Dimens.ListRowVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(Dimens.AvatarList)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CallTypeIcon(group.entries.first().type)
                }
                Spacer(modifier = Modifier.width(Dimens.ListRowGap))
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Formats.listTime(group.entries.first().date),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 132.dp),
                )
                if (group.entries.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) { Text(group.entries.size.toString()) }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    group.entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = onToggle,
                                    onLongClick = { onCopy(group.number) },
                        onLongClickLabel = "Копировать номер",
                                )
                                .heightIn(min = 48.dp)
                                .padding(
                                    start = Dimens.ListRowHorizontal + Dimens.AvatarList + Dimens.ListRowGap,
                                    end = Dimens.ListRowHorizontal,
                                    top = 6.dp,
                                    bottom = 6.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CallTypeIcon(entry.type, size = 18)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = Formats.listTime(entry.date),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = Formats.duration(entry.duration),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallTypeIcon(type: Int, size: Int = 24) {
    val icon = when (type) {
        CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed
        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> Icons.Filled.CallEnd
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val description = when (type) {
        CallLog.Calls.MISSED_TYPE -> "Пропущенный"
        CallLog.Calls.OUTGOING_TYPE -> "Исходящий"
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "Отклонённый"
        else -> "Входящий"
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        modifier = Modifier.size(size.dp),
        tint = if (type == CallLog.Calls.MISSED_TYPE) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
