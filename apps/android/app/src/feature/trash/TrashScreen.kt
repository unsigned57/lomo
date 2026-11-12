package com.lomo.app.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.app.presentation.markdown.MemoMarkdownMediaAdapter
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.Memo
import com.lomo.ui.benchmark.benchmarkAnchor
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.card.MemoCard
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.LomoListExitPhase
import com.lomo.ui.component.common.LomoListExitRenderEntry
import com.lomo.ui.component.common.LomoListRenderWindow
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.component.common.SkeletonMemoItem
import com.lomo.ui.component.common.computeExitRenderKeyWindow
import com.lomo.ui.component.common.computeItemKeyWindow
import com.lomo.ui.component.common.lomoListItemExitPhaseMotion
import com.lomo.ui.component.common.lomoListItemMotion
import com.lomo.ui.component.common.rememberLomoListExitState
import com.lomo.ui.component.menu.ActionItemHaptic
import com.lomo.ui.component.menu.ActionItemPlacement
import com.lomo.ui.component.menu.ActionItemUi
import com.lomo.ui.component.menu.MemoActionSheet
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

private val TRASH_LIST_CONTENT_PADDING = 16.dp
private val TRASH_LIST_ITEM_SPACING = 12.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Hands a committed restore to the main-feed owner so the restored memo is focused when the
     * user returns. Queued at commit time — firing earlier would race the durable mutation.
     */
    onRequestFocusMemo: (String) -> Unit = {},
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
        itemSnapshotList.items
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
            onNavigateToImage = onNavigateToImage,
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
            viewModel.restoreMemo(memo, trashAnchorAfter(overlayItems, memo.id)) { restored ->
                onRequestFocusMemo(restored.id)
            }
        },
        onDeletePermanently = { memo ->
            viewModel.deletePermanently(memo, trashAnchorAfter(overlayItems, memo.id))
        },
        onDismissClearTrashDialog = { showClearTrashDialog = false },
        onConfirmClearTrash = {
            haptic.heavy()
            showClearTrashDialog = false
            viewModel.clearTrash()
        },
    )
}

@Composable
private fun TrashScreenEffects(errorMessage: String?, snackbarHostState: SnackbarHostState, onClearError: () -> Unit) {
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    Scaffold(
        modifier =
        Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .benchmarkAnchorRoot(BenchmarkAnchorContract.TRASH_ROOT),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.trash_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
                navigationIcon = {
                    LomoIconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    LomoIconButton(
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
    snapshotMemos: List<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    snapshotStartIndex: Int,
    onExitSettled: (String) -> Unit,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    listState: LazyListState,
    onMemoMenuClick: (Memo) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isEmpty = trashIsEmpty(pagedItems.itemCount, renderList.map { it.exitPhase })
    Box(modifier = modifier.fillMaxSize()) {
        com.lomo.ui.component.common.LomoStateContent(
            state = isEmpty,
            contentKey = { it },
            modifier = Modifier.fillMaxSize(),
        ) { empty ->
            if (empty) {
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
                    onNavigateToImage = onNavigateToImage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun TrashEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        com.lomo.ui.component.common.EmptyState(
            icon = Icons.Outlined.Delete,
            title = stringResource(R.string.trash_empty_title),
            description = stringResource(R.string.trash_empty_desc),
        )
    }
}

@Composable
private fun TrashMemoList(
    pagedItems: LazyPagingItems<MemoUiModel>,
    overlayIdle: Boolean,
    snapshotMemos: List<MemoUiModel>,
    renderList: ImmutableList<LomoListExitRenderEntry<MemoUiModel>>,
    snapshotStartIndex: Int,
    onExitSettled: (String) -> Unit,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    listState: LazyListState,
    onMemoMenuClick: (Memo) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderWindow = remember(snapshotMemos, renderList, overlayIdle, snapshotStartIndex, pagedItems.itemCount) {
        LomoListRenderWindow(
            sourceItemCount = pagedItems.itemCount,
            startIndex = snapshotStartIndex,
            sourceKeys = snapshotMemos.map { it.memo.id },
            renderKeys = if (overlayIdle) snapshotMemos.map { it.memo.id } else renderList.map { it.item.memo.id },
        )
    }
    val totalItemCount = renderWindow.itemCount
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
            renderWindow.sourceIndexAt(index)?.let { sourceIndex -> pagedItems[sourceIndex] }
            val loadedIndex = index - snapshotStartIndex
            val entry = if (overlayIdle) null else renderList.getOrNull(loadedIndex)
            val uiModel = entry?.item ?: snapshotMemos.getOrNull(loadedIndex)
            if (uiModel != null) {
                val memoId = (entry?.snapshotMemo ?: uiModel).memo.id
                val exitPhase = entry?.exitPhase
                TrashMemoCardItem(
                    uiModel = uiModel,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onMemoMenuClick = onMemoMenuClick,
                    onNavigateToImage = onNavigateToImage,
                    exitPhase = exitPhase,
                    onExitSettled = { onExitSettled(memoId) },
                    modifier =
                    Modifier
                        .lomoListItemMotion(
                            this,
                            animateAppearance = false,
                            animatePlacement = overlayIdle && !listState.isScrollInProgress,
                        )
                        .fillMaxWidth(),
                )
            } else {
                SkeletonMemoItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = TRASH_LIST_ITEM_SPACING),
                )
            }
        }
    }
}

@Composable
private fun TrashMemoCardItem(
    uiModel: MemoUiModel,
    dateFormat: String,
    timeFormat: String,
    freeTextCopyEnabled: Boolean,
    onMemoMenuClick: (Memo) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    exitPhase: LomoListExitPhase?,
    onExitSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .lomoListItemExitPhaseMotion(
                exitPhase = exitPhase,
                onExitSettled = onExitSettled,
            )
            .padding(bottom = TRASH_LIST_ITEM_SPACING)
            .benchmarkAnchor(BenchmarkAnchorContract.memoCard(uiModel.memo.id, isPending = uiModel.memo.isPending)),
    ) {
        MemoCard(
            content = uiModel.memo.content,
            processedContent = uiModel.processedContent,
            renderDocument = uiModel.renderDocument,
            presentationPlan = uiModel.presentationPlan,
            onImageClick = { url -> onNavigateToImage(createImageViewerRequest(uiModel, url)) },
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
private fun TrashClearDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_clear_confirm_title)) },
        text = { Text(stringResource(R.string.trash_clear_confirm_message)) },
        confirmButton = {
            LomoTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_clear))
            }
        },
        dismissButton = {
            LomoTextButton(onClick = onDismiss) {
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
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        MemoActionSheet(
            state = state,
            onDismiss = onDismiss,
            benchmarkRootTag = BenchmarkAnchorContract.MEMO_MENU_ROOT,
            actions =
            persistentListOf(
                ActionItemUi(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.action_restore),
                    benchmarkTag = BenchmarkAnchorContract.TRASH_ACTION_RESTORE,
                    onClick = onRestore,
                    placement = ActionItemPlacement.Capsule,
                    dismissAfterClick = true,
                    haptic = ActionItemHaptic.MEDIUM,
                ),
                ActionItemUi(
                    icon = Icons.Default.DeleteForever,
                    label = stringResource(R.string.action_delete_permanently),
                    benchmarkTag = BenchmarkAnchorContract.TRASH_ACTION_DELETE_PERMANENTLY,
                    onClick = onDeletePermanently,
                    placement = ActionItemPlacement.Capsule,
                    isDestructive = true,
                    dismissAfterClick = true,
                    haptic = ActionItemHaptic.HEAVY,
                ),
            ),
        )
    }
}
