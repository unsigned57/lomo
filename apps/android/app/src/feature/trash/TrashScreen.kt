package com.lomo.app.feature.trash

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.util.injectedKoinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.R
import com.lomo.app.feature.common.DeleteAnimationItem
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.presentation.markdown.MemoMarkdownMediaAdapter
import com.lomo.domain.model.Memo
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.common.LomoListExitRenderEntry
import com.lomo.ui.component.common.LomoListExitPhase
import com.lomo.ui.component.common.computeExitRenderKeyWindow
import com.lomo.ui.component.common.computeItemKeyWindow
import com.lomo.ui.component.common.lomoListItemExitPhaseMotion
import com.lomo.ui.component.common.lomoListItemMotion
import com.lomo.ui.component.common.rememberLomoListExitState
import com.lomo.ui.component.common.SkeletonMemoItem
import com.lomo.ui.component.menu.ActionItemHaptic
import com.lomo.ui.component.menu.ActionItemUi
import com.lomo.ui.component.menu.MemoActionSheet
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems

private val TRASH_LIST_CONTENT_PADDING = 16.dp
private val TRASH_LIST_ITEM_SPACING = 12.dp
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
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = injectedKoinViewModel(),
) {
    val pagedItems = viewModel.pagedUiMemos.collectAsLazyPagingItems()
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

    val itemSnapshotList = pagedItems.itemSnapshotList
    val snapshotStartIndex = itemSnapshotList.placeholdersBefore
    val snapshotMemos = remember(itemSnapshotList) {
        itemSnapshotList.items.toImmutableList()
    }

    val trashExitState =
        rememberLomoListExitState(
            registry = viewModel.exitAnimationRegistry,
            allItems = snapshotMemos,
            itemKey = { it.memo.id },
        )

    val onTrashExitSettled = trashExitState.onExitSettled

    TrashScreenEffects(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )

    TrashScreenScaffold(
        snackbarHostState = snackbarHostState,
        hasItems = snapshotMemos.isNotEmpty() || pagedItems.itemCount > 0,
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
            pagedItems = pagedItems,
            overlayIdle = trashExitState.overlayIdle,
            snapshotMemos = snapshotMemos,
            renderList = trashExitState.renderList,
            snapshotStartIndex = snapshotStartIndex,
            onExitSettled = onTrashExitSettled,
            dateFormat = appPreferences.dateFormat,
            timeFormat = appPreferences.timeFormat,
            freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
            listState = listState,
            onMemoMenuClick = { memo ->
                haptic.medium()
                selectedMemo = memo
            },
            modifier = modifier.padding(paddingValues),
        )
    }

    val overlayItems =
        trashOverlayItems(
            overlayIdle = trashExitState.overlayIdle,
            snapshotMemos = snapshotMemos,
            renderList = trashExitState.renderList,
        )
    TrashScreenDialogs(
        selectedMemo = selectedMemo,
        showClearTrashDialog = showClearTrashDialog,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        onDismissActionSheet = { selectedMemo = null },
        onRestoreMemo = { memo ->
            viewModel.restoreMemo(memo, trashAnchorAfter(overlayItems, memo.id))
        },
        onDeletePermanently = { memo ->
            viewModel.deletePermanently(memo, trashAnchorAfter(overlayItems, memo.id))
        },
        onDismissClearTrashDialog = { showClearTrashDialog = false },
        onConfirmClearTrash = {
            haptic.heavy()
            showClearTrashDialog = false
            val items =
                overlayItems.mapIndexed { index, uiModel ->
                    DeleteAnimationItem(
                        id = uiModel.memo.id,
                        snapshot = uiModel.memo,
                        anchoredAfterKey = if (index > 0) overlayItems[index - 1].memo.id else null,
                    )
                }
            viewModel.clearTrash(items)
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
        modifier =
            Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .benchmarkAnchorRoot(BenchmarkAnchorContract.TRASH_ROOT),
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
    pagedItems: LazyPagingItems<MemoUiModel>,
    overlayIdle: Boolean,
    snapshotMemos: ImmutableList<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    snapshotStartIndex: Int,
    onExitSettled: (String) -> Unit,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    listState: LazyListState,
    onMemoMenuClick: (Memo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (snapshotMemos.isEmpty() && renderList.isEmpty() && pagedItems.itemCount == 0) {
            TrashEmptyState(modifier = Modifier.fillMaxSize())
        } else {
            TrashMemoList(
                pagedItems = pagedItems,
                overlayIdle = overlayIdle,
                snapshotMemos = snapshotMemos,
                renderList = renderList,
                snapshotStartIndex = snapshotStartIndex,
                onExitSettled = onExitSettled,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                freeTextCopyEnabled = freeTextCopyEnabled,
                listState = listState,
                onMemoMenuClick = onMemoMenuClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
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
    pagedItems: LazyPagingItems<MemoUiModel>,
    overlayIdle: Boolean,
    snapshotMemos: ImmutableList<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    snapshotStartIndex: Int,
    onExitSettled: (String) -> Unit,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    listState: LazyListState,
    onMemoMenuClick: (Memo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadedCount = if (overlayIdle) snapshotMemos.size else renderList.size
    val totalItemCount = maxOf(snapshotStartIndex + loadedCount, pagedItems.itemCount)
    val uniqueKeys =
        remember(overlayIdle, snapshotStartIndex, snapshotMemos, renderList, pagedItems.itemSnapshotList) {
            if (overlayIdle) {
                computeItemKeyWindow(
                    snapshotStartIndex = snapshotStartIndex,
                    keys = snapshotMemos.map { uiModel -> uiModel.memo.id },
                )
            } else {
                computeExitRenderKeyWindow(
                    snapshotStartIndex = snapshotStartIndex,
                    renderList = renderList,
                    itemKey = { uiModel -> uiModel.memo.id },
                )
            }
        }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(TRASH_LIST_CONTENT_PADDING),
        verticalArrangement = Arrangement.Top,
        modifier = modifier,
    ) {
        items(
            count = totalItemCount,
            key = { index -> uniqueKeys.keyAt(index) },
        ) { index ->
            if (index < pagedItems.itemCount) {
                pagedItems[index]
            }
            val loadedIndex = index - snapshotStartIndex
            val entry = if (overlayIdle) null else renderList.getOrNull(loadedIndex)
            val uiModel = entry?.item ?: snapshotMemos.getOrNull(loadedIndex)
            if (uiModel != null) {
                val isLast = index == totalItemCount - 1
                val memoId = (entry?.snapshotMemo ?: uiModel).memo.id
                val exitPhase = entry?.exitPhase
                val isExiting = exitPhase != null
                TrashMemoCardItem(
                    uiModel = uiModel,
                    bottomSpacing = if (isLast) 0.dp else TRASH_LIST_ITEM_SPACING,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onMemoMenuClick = onMemoMenuClick,
                    exitPhase = exitPhase,
                    onExitSettled = { onExitSettled(memoId) },
                    modifier =
                        Modifier
                            .lomoListItemMotion(this, animatePlacement = !isExiting)
                            .fillMaxWidth(),
                )
            } else {
                val isLast = index == totalItemCount - 1
                SkeletonMemoItem(
                    modifier = Modifier
                        .lomoListItemMotion(this)
                        .fillMaxWidth()
                        .padding(bottom = if (isLast) 0.dp else TRASH_LIST_ITEM_SPACING),
                )
            }
        }
    }
}

@Composable
private fun TrashMemoCardItem(
    uiModel: MemoUiModel,
    bottomSpacing: androidx.compose.ui.unit.Dp,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    onMemoMenuClick: (Memo) -> Unit,
    exitPhase: LomoListExitPhase?,
    onExitSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(bottom = bottomSpacing)
            .lomoListItemExitPhaseMotion(
                exitPhase = exitPhase,
                onExitSettled = onExitSettled,
            )
            .benchmarkAnchor(BenchmarkAnchorContract.memoCard(uiModel.memo.id)),
    ) {
        MemoCard(
            content = uiModel.memo.content,
            processedContent = uiModel.processedContent,
            renderDocument = uiModel.renderDocument,
            timestamp = uiModel.memo.timestamp,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            isPinned = uiModel.memo.isPinned,
            tags = uiModel.tags,
            collapsedSummary = uiModel.collapsedSummary,
            allowFreeTextCopy = freeTextCopyEnabled,
            menuButtonModifier = Modifier.benchmarkAnchor(BenchmarkAnchorContract.memoMenu(uiModel.memo.id)),
            onMenuClick = { onMemoMenuClick(uiModel.memo) },
            menuContent = {},
            mediaPresentationResolver = MemoMarkdownMediaAdapter.resolver,
            mediaContent = MemoMarkdownMediaAdapter.content,
        )
    }
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
            onDismiss = onDismiss,
            useHorizontalScroll = false,
            showSwipeAffordance = false,
            equalWidthActions = true,
            benchmarkRootTag = BenchmarkAnchorContract.MEMO_MENU_ROOT,
            actions =
                persistentListOf(
                    ActionItemUi(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = stringResource(R.string.action_restore),
                        benchmarkTag = BenchmarkAnchorContract.TRASH_ACTION_RESTORE,
                        onClick = onRestore,
                        dismissAfterClick = true,
                        haptic = ActionItemHaptic.MEDIUM,
                    ),
                    ActionItemUi(
                        icon = Icons.Default.DeleteForever,
                        label = stringResource(R.string.action_delete_permanently),
                        benchmarkTag = BenchmarkAnchorContract.TRASH_ACTION_DELETE_PERMANENTLY,
                        onClick = onDeletePermanently,
                        isDestructive = true,
                        dismissAfterClick = true,
                        haptic = ActionItemHaptic.HEAVY,
                    ),
                ),
        )
    }
}
