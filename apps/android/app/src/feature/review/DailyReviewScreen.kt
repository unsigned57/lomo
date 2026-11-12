package com.lomo.app.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuContextAction
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.existingMemoEditorSurface
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberAutoSaveCommittedNotifier
import com.lomo.app.feature.memo.rememberFullMemoEditorOpener
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.util.injectedKoinViewModel
import com.lomo.domain.model.Memo
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.LomoPageIndicator
import com.lomo.ui.component.common.WithDraggableScrollbar
import com.lomo.ui.theme.AppSpacing
import com.lomo.ui.util.LocalAppHapticFeedback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReviewScreen(
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    lanShareEnabled: Boolean = true,
    onRequestFocusMemo: (String) -> Unit = {},
    onNavigateToMain: () -> Unit = onBackClick,
    viewModel: DailyReviewViewModel = injectedKoinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readyOptions = (uiState as? DailyReviewScreenState.Ready)?.options
    var retainedOptions by remember { mutableStateOf(readyOptions) }
    if (readyOptions != null) {
        retainedOptions = readyOptions
    }
    val openOptions =
        rememberDailyReviewOptionsAction(
            options = retainedOptions,
            onOptionsChange = { updated ->
                retainedOptions = updated
                viewModel.updateReviewOptions(updated)
            },
        )
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val rootDirectory by viewModel.rootDirectory.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDirectory.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
    val stableImageMap = remember(imageMap) { imageMap.toImmutableMap() }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val restoredPageIndex by viewModel.restoredPageIndex.collectAsStateWithLifecycle()
    val haptic = LocalAppHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val editorController = rememberMemoEditorController()
    val openFullMemoEditor =
        rememberFullMemoEditorOpener(editorController, viewModel::reportError)

    DailyReviewErrorEffect(errorMessage, snackbarHostState, viewModel::clearError)
    val mainViewModel: com.lomo.app.feature.main.MainViewModel = com.lomo.app.util.activityKoinViewModel()
    val memoMenuCommandHandler =
        rememberDailyReviewMenuCommands(
            appPreferences,
            viewModel,
            mainViewModel,
            openFullMemoEditor,
            lanShareEnabled,
            onNavigateToShare,
            onRequestFocusMemo,
            onNavigateToMain,
        )

    val editorSurface =
        dailyReviewMemoEditorSurface(
            imageDirectory = imageDirectory,
            rootDirectory = rootDirectory,
            imageMap = stableImageMap,
            dateFormat = appPreferences.dateFormat,
            timeFormat = appPreferences.timeFormat,
            toolbarToolOrder = appPreferences.inputToolbarToolOrder,
            viewModel = viewModel,
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
        onEditorOpenFailure = viewModel::reportError,
        onAutoSaveCommitted = rememberAutoSaveCommittedNotifier(snackbarHostState),
    ) { showMenu, openEditor ->
        DailyReviewScreenScaffold(
            onFilterClick = openOptions,
            filterEnabled = retainedOptions != null,
            isFilterActive = retainedOptions != null && retainedOptions != com.lomo.domain.model.DailyReviewOptions(),
            onBackClick = onBackClick,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            snackbarHostState = snackbarHostState,
        ) { scaffoldPadding ->
            DailyReviewScreenContent(
                onRetry = viewModel.retry,
                uiState = uiState,
                restoredPageIndex = restoredPageIndex,
                dateFormat = appPreferences.dateFormat,
                timeFormat = appPreferences.timeFormat,
                doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
                freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
                padding = scaffoldPadding,
                onShowMenu = showMenu,
                onOpenEditor = openEditor,
                onTodoClick = viewModel::toggleTodo,
                onNavigateToImage = onNavigateToImage,
                onLoadMore = viewModel::loadMore,
                onPageChanged = viewModel::onPageChanged,
                haptic = haptic,
            )
        }
    }
    com.lomo.app.feature.main.MainVersionHistoryEffectHost(mainViewModel = mainViewModel)
}

private fun dailyReviewMemoEditorSurface(
    imageDirectory: String?,
    rootDirectory: String?,
    imageMap: kotlinx.collections.immutable.ImmutableMap<String, android.net.Uri>,
    dateFormat: String,
    timeFormat: String,
    toolbarToolOrder: ImmutableList<String>,
    viewModel: DailyReviewViewModel,
) = existingMemoEditorSurface(
    session =
    MemoEditorSessionState(
        imageDirectory = imageDirectory,
        ownerDraftId = viewModel.ownerDraftId,
        rootPath = rootDirectory,
        imageMap = imageMap,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
    ),
    toolbarToolOrder = toolbarToolOrder,
    onUpdateMemo = viewModel.editorSubmission::submit,
    submissionState = viewModel.editorSubmission.state,
    onSaveImages = viewModel::saveImages,
    onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyReviewScreenScaffold(
    onFilterClick: () -> Unit,
    filterEnabled: Boolean,
    isFilterActive: Boolean,
    onBackClick: () -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    snackbarHostState: SnackbarHostState,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier =
        Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .benchmarkAnchorRoot(BenchmarkAnchorContract.DAILY_REVIEW_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.sidebar_daily_review),
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
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    LomoIconButton(
                        onClick = onFilterClick,
                        enabled = filterEnabled,
                        colors =
                        androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                            containerColor =
                            if (isFilterActive) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                            contentColor =
                            if (isFilterActive) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    ) {
                        Icon(
                            Icons.Rounded.FilterAlt,
                            contentDescription = androidx.compose.ui.res.stringResource(R.string.review_options_title),
                        )
                    }
                },
                colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        content = content,
    )
}

@Composable
private fun DailyReviewScreenContent(
    onRetry: () -> Unit,
    uiState: DailyReviewScreenState,
    restoredPageIndex: Int,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    padding: PaddingValues,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onLoadMore: () -> Unit,
    onPageChanged: (Int) -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        com.lomo.ui.component.common.LomoStateContent(
            state = uiState,
            contentKey = { it::class to (it is DailyReviewScreenState.Ready && it.memos.isEmpty()) },
            modifier = Modifier.fillMaxSize(),
        ) { state ->
            when (state) {
                is DailyReviewScreenState.Loading -> ExpressiveContainedLoadingIndicator()

                is DailyReviewScreenState.Failed -> {
                    Column {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(AppSpacing.Medium),
                        )
                        com.lomo.ui.component.common.LomoTextButton(onClick = onRetry) {
                            Text(androidx.compose.ui.res.stringResource(R.string.action_retry))
                        }
                    }
                }

                is DailyReviewScreenState.Ready -> {
                    val memos = state.memos
                    if (memos.isEmpty()) {
                        EmptyState(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            title = androidx.compose.ui.res.stringResource(
                                if (state.options.filter.isActive) {
                                    R.string.review_no_matches_title
                                } else {
                                    R.string.review_no_memos_title
                                },
                            ),
                            description = androidx.compose.ui.res.stringResource(
                                if (state.options.filter.isActive) {
                                    R.string.review_no_matches_desc
                                } else {
                                    R.string.review_no_memos_desc
                                },
                            ),
                        )
                    } else {
                        DailyReviewPager(
                            memos = remember(memos) { memos.toImmutableList() },
                            restoredPageIndex = restoredPageIndex,
                            dateFormat = dateFormat,
                            timeFormat = timeFormat,
                            doubleTapEditEnabled = doubleTapEditEnabled,
                            freeTextCopyEnabled = freeTextCopyEnabled,
                            onShowMenu = onShowMenu,
                            onOpenEditor = onOpenEditor,
                            onTodoClick = onTodoClick,
                            onNavigateToImage = onNavigateToImage,
                            onRequestMore = onLoadMore,
                            onPageChanged = onPageChanged,
                            haptic = haptic,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyReviewPager(
    memos: ImmutableList<com.lomo.app.feature.main.MemoUiModel>,
    restoredPageIndex: Int,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    onRequestMore: () -> Unit,
    onPageChanged: (Int) -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { memos.size })
    var previousPage by remember { mutableIntStateOf(0) }

    LaunchedEffect(restoredPageIndex, memos.size) {
        if (memos.isEmpty()) {
            return@LaunchedEffect
        }
        val targetPage = restoredPageIndex.coerceIn(0, memos.lastIndex)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage, memos.size) {
        if (pagerState.currentPage != previousPage) {
            previousPage = pagerState.currentPage
            haptic.light()
        }
        onPageChanged(pagerState.currentPage)
        if (pagerState.currentPage > 0 && pagerState.currentPage == memos.lastIndex) {
            onRequestMore()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = AppSpacing.Large),
            pageSpacing = AppSpacing.Medium,
            modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            DailyReviewPagerPage(
                memo = memos[page],
                page = page,
                totalPages = memos.size,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onShowMenu = onShowMenu,
                onOpenEditor = onOpenEditor,
                onTodoClick = onTodoClick,
                onNavigateToImage = onNavigateToImage,
            )
        }
    }
}

@Composable
private fun DailyReviewPagerPage(
    memo: com.lomo.app.feature.main.MemoUiModel,
    page: Int,
    totalPages: Int,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onTodoClick: (Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    val scrollState = rememberScrollState()
    val onMemoImageClick =
        remember(memo, onNavigateToImage) {
            { url: String ->
                onNavigateToImage(
                    createImageViewerRequest(
                        memo = memo,
                        clickedUrl = url,
                    ),
                )
            }
        }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        WithDraggableScrollbar(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .padding(bottom = AppSpacing.ExtraLarge)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MemoCardEntry(
                        uiModel = memo,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        doubleTapEditEnabled = doubleTapEditEnabled,
                        freeTextCopyEnabled = freeTextCopyEnabled,
                        onMemoEdit = onOpenEditor,
                        onShowMenu = onShowMenu,
                        onImageClick = onMemoImageClick,
                        onTodoClick = { actionSpan ->
                            onTodoClick(memo.memo, actionSpan)
                        },
                    )

                    LomoPageIndicator(
                        current = page,
                        total = totalPages,
                        modifier = Modifier.padding(top = AppSpacing.Medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyReviewErrorEffect(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onConsumed()
        }
    }
}

@Composable
private fun rememberDailyReviewOptionsAction(
    options: com.lomo.domain.model.DailyReviewOptions?,
    onOptionsChange: (com.lomo.domain.model.DailyReviewOptions) -> Unit,
): () -> Unit {
    var visible by remember { mutableStateOf(false) }
    if (visible && options != null) {
        DailyReviewOptionsSheet(
            options = options,
            onOptionsChange = onOptionsChange,
            onDismiss = { visible = false },
        )
    }
    return remember { { visible = true } }
}

@Composable
private fun rememberDailyReviewMenuCommands(
    appPreferences: com.lomo.app.feature.preferences.AppPreferencesState,
    viewModel: DailyReviewViewModel,
    mainViewModel: com.lomo.app.feature.main.MainViewModel,
    openFullMemoEditor: (String) -> Unit,
    lanShareEnabled: Boolean,
    onNavigateToShare: (String, Long) -> Unit,
    onRequestFocusMemo: (String) -> Unit,
    onNavigateToMain: () -> Unit,
): com.lomo.app.feature.memo.MemoMenuCommandHandler = rememberMemoMenuCommandHandler(
    presentationState =
    MemoMenuPresentationState(
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        customFontPath = appPreferences.customFontPath,
    ),
    onEditMemo = openFullMemoEditor,
    onDeleteMemo = viewModel::deleteMemo,
    onLanShare =
    if (lanShareEnabled) {
        { request -> onNavigateToShare(request.content, request.timestamp) }
    } else {
        null
    },
    contextAction =
    MemoMenuContextAction.Jump { state ->
        handleMemoJumpToMain(
            selection = state,
            requestFocusMemo = onRequestFocusMemo,
            navigateToMain = onNavigateToMain,
        )
    },
    onVersionHistory = { selection -> mainViewModel.loadVersionHistory(selection.memo) },
)
