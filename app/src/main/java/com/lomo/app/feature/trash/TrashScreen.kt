package com.lomo.app.feature.trash

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.domain.model.Memo
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.menu.MemoActionHaptic
import com.lomo.ui.component.menu.MemoActionSheet
import com.lomo.ui.component.menu.MemoActionSheetAction
import com.lomo.ui.component.menu.MemoMenuState

private val TRASH_LIST_CONTENT_PADDING = 16.dp
private val TRASH_LIST_ITEM_SPACING = 12.dp
private const val TRASH_DELETE_ANIMATION_DURATION_MILLIS = 300
private const val TRASH_DELETE_FADE_DELAY_MILLIS = 300
private const val TRASH_ITEM_VISIBLE_ALPHA = 1f
private const val TRASH_ITEM_HIDDEN_ALPHA = 0f
private const val TRASH_ITEM_ALPHA_THRESHOLD = 0.999f
private val TRASH_ACTION_HANDLE_PADDING = 22.dp
private val TRASH_ACTION_HANDLE_WIDTH = 32.dp
private val TRASH_ACTION_HANDLE_SIZE = 32.dp
private val TRASH_ACTION_HANDLE_HEIGHT = 4.dp
private val TRASH_ACTION_HANDLE_CORNER = 999.dp
private const val TRASH_ACTION_HANDLE_ALPHA = 0.4f

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val trashMemos by viewModel.trashUiMemos.collectAsStateWithLifecycle()
    val deletingMemoIds by viewModel.deletingMemoIds.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val listState =
        rememberSaveable(saver = LazyListState.Saver) {
            LazyListState()
        }
    var selectedMemo by remember { mutableStateOf<Memo?>(null) }
    var showClearTrashDialog by rememberSaveable { mutableStateOf(false) }

    TrashScreenEffects(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )

    TrashScreenScaffold(
        snackbarHostState = snackbarHostState,
        hasItems = trashMemos.isNotEmpty(),
        onBackClick = {
            haptic.medium()
            onBackClick()
        },
        onClearTrashClick = {
            haptic.heavy()
            showClearTrashDialog = true
        },
    ) { paddingValues ->
        TrashScreenContent(
            trashMemos = trashMemos,
            deletingMemoIds = deletingMemoIds,
            dateFormat = appPreferences.dateFormat,
            timeFormat = appPreferences.timeFormat,
            freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
            listState = listState,
            onMemoMenuClick = { memo ->
                haptic.medium()
                selectedMemo = memo
            },
            modifier = Modifier.padding(paddingValues),
        )
    }

    TrashScreenDialogs(
        selectedMemo = selectedMemo,
        showClearTrashDialog = showClearTrashDialog,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        onDismissActionSheet = { selectedMemo = null },
        onRestoreMemo = viewModel::restoreMemo,
        onDeletePermanently = viewModel::deletePermanently,
        onDismissClearTrashDialog = { showClearTrashDialog = false },
        onConfirmClearTrash = {
            haptic.heavy()
            showClearTrashDialog = false
            viewModel.clearTrash()
        },
    )
}

@Composable
private fun TrashScreenEffects(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            onClearError()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreenScaffold(
    snackbarHostState: SnackbarHostState,
    hasItems: Boolean,
    onBackClick: () -> Unit,
    onClearTrashClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = hasItems,
                        onClick = onClearTrashClick,
                    ) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.cd_clear_trash),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
        content = content,
    )
}

@Composable
private fun TrashScreenContent(
    trashMemos: List<MemoUiModel>,
    deletingMemoIds: Set<String>,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    listState: LazyListState,
    onMemoMenuClick: (Memo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trashMemos.isEmpty()) {
        TrashEmptyState(modifier = modifier.fillMaxSize())
        return
    }

    TrashMemoList(
        trashMemos = trashMemos,
        deletingMemoIds = deletingMemoIds,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        freeTextCopyEnabled = freeTextCopyEnabled,
        listState = listState,
        onMemoMenuClick = onMemoMenuClick,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun TrashEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        com.lomo.ui.component.common.EmptyState(
            icon = Icons.Rounded.DeleteSweep,
            title = stringResource(R.string.trash_empty_title),
            description = stringResource(R.string.trash_empty_desc),
        )
    }
}

@Composable
private fun TrashMemoList(
    trashMemos: List<MemoUiModel>,
    deletingMemoIds: Set<String>,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    listState: LazyListState,
    onMemoMenuClick: (Memo) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(TRASH_LIST_CONTENT_PADDING),
        verticalArrangement = Arrangement.spacedBy(TRASH_LIST_ITEM_SPACING),
        modifier = modifier,
    ) {
        items(
            items = trashMemos,
            key = { it.memo.id },
            contentType = { "memo" },
        ) { uiModel ->
            TrashMemoCardItem(
                uiModel = uiModel,
                isDeleting = uiModel.memo.id in deletingMemoIds,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onMemoMenuClick = onMemoMenuClick,
                modifier =
                    Modifier.animateItem(
                        fadeInSpec =
                            keyframes {
                                durationMillis = TRASH_DELETE_ANIMATION_DURATION_MILLIS
                                TRASH_ITEM_HIDDEN_ALPHA at 0
                                TRASH_ITEM_HIDDEN_ALPHA at TRASH_DELETE_FADE_DELAY_MILLIS
                                TRASH_ITEM_VISIBLE_ALPHA at TRASH_DELETE_ANIMATION_DURATION_MILLIS using
                                    com.lomo.ui.theme.MotionTokens.EasingEmphasizedDecelerate
                            },
                        fadeOutSpec = null,
                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TrashMemoCardItem(
    uiModel: MemoUiModel,
    isDeleting: Boolean,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    onMemoMenuClick: (Memo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteAlpha by animateFloatAsState(
        targetValue =
            if (isDeleting) {
                TRASH_ITEM_HIDDEN_ALPHA
            } else {
                TRASH_ITEM_VISIBLE_ALPHA
            },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = TRASH_DELETE_ANIMATION_DURATION_MILLIS,
                easing = com.lomo.ui.theme.MotionTokens.EasingStandard,
            ),
        label = "DeleteAlpha",
    )

    Box(
        modifier =
            modifier
                .then(trashItemDeleteModifier(deleteAlpha)),
    ) {
        MemoCard(
            content = uiModel.memo.content,
            processedContent = uiModel.processedContent,
            precomputedNode = uiModel.markdownNode,
            timestamp = uiModel.memo.timestamp,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            isPinned = uiModel.memo.isPinned,
            tags = uiModel.tags,
            allowFreeTextCopy = freeTextCopyEnabled,
            onMenuClick = { onMemoMenuClick(uiModel.memo) },
        )
    }
}

private fun trashItemDeleteModifier(deleteAlpha: Float): Modifier =
    if (deleteAlpha < TRASH_ITEM_ALPHA_THRESHOLD) {
        Modifier.graphicsLayer {
            alpha = deleteAlpha
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
    } else {
        Modifier
    }

@Composable
private fun TrashScreenDialogs(
    selectedMemo: Memo?,
    showClearTrashDialog: Boolean,
    dateFormat: String,
    timeFormat: String,
    onDismissActionSheet: () -> Unit,
    onRestoreMemo: (Memo) -> Unit,
    onDeletePermanently: (Memo) -> Unit,
    onDismissClearTrashDialog: () -> Unit,
    onConfirmClearTrash: () -> Unit,
) {
    selectedMemo?.let { memo ->
        val menuState = remember(memo, dateFormat, timeFormat) { memoMenuState(memo, dateFormat, timeFormat) }
        TrashActionSheet(
            state = menuState,
            onDismiss = onDismissActionSheet,
            onRestore = { onRestoreMemo(memo) },
            onDeletePermanently = { onDeletePermanently(memo) },
        )
    }

    if (showClearTrashDialog) {
        TrashClearDialog(
            onDismiss = onDismissClearTrashDialog,
            onConfirm = onConfirmClearTrash,
        )
    }
}

@Composable
private fun TrashClearDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_clear_confirm_title)) },
        text = { Text(stringResource(R.string.trash_clear_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashActionSheet(
    state: MemoMenuState,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier =
                    Modifier
                        .padding(vertical = TRASH_ACTION_HANDLE_PADDING)
                        .width(TRASH_ACTION_HANDLE_WIDTH)
                        .size(TRASH_ACTION_HANDLE_SIZE, TRASH_ACTION_HANDLE_HEIGHT)
                        .clip(RoundedCornerShape(TRASH_ACTION_HANDLE_CORNER))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TRASH_ACTION_HANDLE_ALPHA),
                        ),
            )
        },
    ) {
        MemoActionSheet(
            state = state,
            onCopy = {},
            onShareImage = {},
            onShareText = {},
            onLanShare = {},
            onEdit = {},
            onDelete = onDeletePermanently,
            onDismiss = onDismiss,
            useHorizontalScroll = false,
            showSwipeAffordance = false,
            equalWidthActions = true,
            actions =
                listOf(
                    MemoActionSheetAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = stringResource(R.string.action_restore),
                        onClick = onRestore,
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.MEDIUM,
                    ),
                    MemoActionSheetAction(
                        icon = Icons.Default.DeleteForever,
                        label = stringResource(R.string.action_delete_permanently),
                        onClick = onDeletePermanently,
                        isDestructive = true,
                        dismissAfterClick = true,
                        haptic = MemoActionHaptic.HEAVY,
                    ),
                ),
        )
    }
}
