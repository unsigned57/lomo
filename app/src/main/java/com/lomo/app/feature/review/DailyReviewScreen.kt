package com.lomo.app.feature.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lomo.app.util.injectedKoinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.R
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.common.UiState
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.image.createImageViewerRequest
import com.lomo.app.feature.memo.MemoCardEntry
import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.existingMemoEditorSurface
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.domain.model.Memo
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.EmptyState
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
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val dateFormat = appPreferences.dateFormat
    val timeFormat = appPreferences.timeFormat
    val shareCardShowTime = appPreferences.shareCardShowTime
    val shareCardShowSignature = appPreferences.shareCardShowBrand
    val shareCardSignatureText = appPreferences.shareCardSignatureText
    val doubleTapEditEnabled = appPreferences.doubleTapEditEnabled
    val freeTextCopyEnabled = appPreferences.freeTextCopyEnabled
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

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
            presentationState =
                MemoMenuPresentationState(
                    shareCardShowTime = shareCardShowTime,
                    shareCardShowSignature = shareCardShowSignature,
                    shareCardSignatureText = shareCardSignatureText,
                    customFontPath = appPreferences.customFontPath,
                    showJump = true,
                    memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
                    memoActionOrder = appPreferences.memoActionOrderFor(MemoActionOrderScopes.REVIEW),
                ),
            onEditMemo = editorController::openForEdit,
            onDeleteMemo = viewModel::deleteMemo,
            onLanShare =
                if (lanShareEnabled) {
                    { request -> onNavigateToShare(request.content, request.timestamp) }
                } else {
                    null
                },
            onJump = { state ->
                handleMemoJumpToMain(
                    selection = state,
                    requestFocusMemo = onRequestFocusMemo,
                    navigateToMain = onNavigateToMain,
                )
            },
            onMemoActionInvoked = viewModel::recordMemoActionUsage,
            onMemoActionOrderChanged = viewModel.updateMemoActionOrder,
        )

    val editorSurface =
        dailyReviewMemoEditorSurface(
            imageDirectory = imageDirectory,
            rootDirectory = rootDirectory,
            imageMap = stableImageMap,
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            toolbarToolOrder = appPreferences.inputToolbarToolOrder,
            viewModel = viewModel,
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
    ) { showMenu, openEditor ->
        DailyReviewScreenScaffold(
            onBackClick = onBackClick,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            snackbarHostState = snackbarHostState,
        ) { scaffoldPadding ->
            DailyReviewScreenContent(
                uiState = uiState,
                restoredPageIndex = restoredPageIndex,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
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
}

private fun dailyReviewMemoEditorSurface(
    imageDirectory: String?,
    rootDirectory: String?,
    imageMap: kotlinx.collections.immutable.ImmutableMap<String, android.net.Uri>,
    dateFormat: String,
    timeFormat: String,
    toolbarToolOrder: ImmutableList<String>,
    viewModel: DailyReviewViewModel,
) =
    existingMemoEditorSurface(
        session =
            MemoEditorSessionState(
                imageDirectory = imageDirectory,
                rootPath = rootDirectory,
                imageMap = imageMap,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
            ),
        toolbarToolOrder = toolbarToolOrder,
        onUpdateMemo = viewModel::updateMemo,
        onSaveImage = viewModel::saveImage,
        onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyReviewScreenScaffold(
    onBackClick: () -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    snackbarHostState: SnackbarHostState,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(R.string.sidebar_daily_review)) },
                navigationIcon = {
                    IconButton(
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
    uiState: UiState<List<com.lomo.app.feature.main.MemoUiModel>>,
    restoredPageIndex: Int,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    padding: PaddingValues,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onOpenEditor: (Memo) -> Unit,
    onTodoClick: (Memo, Int, Boolean) -> Unit,
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
        when (val state = uiState) {
            is UiState.Loading -> ExpressiveContainedLoadingIndicator()

            is UiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(AppSpacing.Medium),
                )
            }

            is UiState.Success -> {
                val memos = state.data
                if (memos.isEmpty()) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        title = androidx.compose.ui.res.stringResource(R.string.review_no_memos_title),
                        description = androidx.compose.ui.res.stringResource(R.string.review_no_memos_desc),
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

            else -> Unit
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
    onTodoClick: (Memo, Int, Boolean) -> Unit,
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
    onTodoClick: (Memo, Int, Boolean) -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
) {
    val scrollState = rememberScrollState()
    val onMemoImageClick =
        remember(memo.imageUrls, onNavigateToImage) {
            { url: String ->
                onNavigateToImage(
                    createImageViewerRequest(
                        imageUrls = memo.imageUrls,
                        clickedUrl = url,
                        memoId = memo.memo.id,
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
                        onTodoClick = { lineIndex, checked ->
                            onTodoClick(memo.memo, lineIndex, checked)
                        },
                    )

                    Text(
                        text = "${page + 1} / $totalPages",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = AppSpacing.Medium),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
