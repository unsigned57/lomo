package com.lomo.app.feature.tag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.memo.MemoCardList
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
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.component.common.LomoStateContent
import com.lomo.ui.component.common.LomoTextButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.toImmutableMap

private val TAG_FILTER_ICON_BADGE_SIZE = 32.dp
private val TAG_FILTER_ICON_SIZE = 18.dp
private val TAG_FILTER_LIST_PADDING = 16.dp
private val TAG_FILTER_LIST_BOTTOM_PADDING = 88.dp

internal enum class TagFilterContentState {
    Loading,
    Empty,
    Error,
    List,
}

internal fun resolveTagFilterContentState(itemCount: Int, refreshState: LoadState): TagFilterContentState = when {
    itemCount > 0 -> TagFilterContentState.List

    refreshState is LoadState.Loading -> TagFilterContentState.Loading

    refreshState is LoadState.Error -> TagFilterContentState.Error

    refreshState is LoadState.NotLoading && !refreshState.endOfPaginationReached ->
        TagFilterContentState.Loading

    else -> TagFilterContentState.Empty
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagFilterScreen(
    tagName: String,
    onBackClick: () -> Unit,
    onNavigateToImage: (ImageViewerRequest) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onRequestFocusMemo: (String) -> Unit = {},
    onNavigateToMain: () -> Unit = onBackClick,
    onNavigateToTag: (String) -> Unit = {},
    lanShareEnabled: Boolean = true,
    viewModel: TagFilterViewModel = injectedKoinViewModel(),
) {
    val pagedItems = viewModel.pagedUiMemos.collectAsLazyPagingItems()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val rootDirectory by viewModel.rootDir.collectAsStateWithLifecycle()
    val imageDirectory by viewModel.imageDir.collectAsStateWithLifecycle()
    val imageMap by viewModel.imageMap.collectAsStateWithLifecycle()
    val stableImageMap = remember(imageMap) { imageMap.toImmutableMap() }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current
    val editorController = rememberMemoEditorController()
    val openFullMemoEditor =
        rememberFullMemoEditorOpener(editorController, viewModel::reportError)

    TagFilterScreenEffects(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )
    val mainViewModel: com.lomo.app.feature.main.MainViewModel = com.lomo.app.util.activityKoinViewModel()
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
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

    val editorSurface =
        existingMemoEditorSurface(
            session =
            MemoEditorSessionState(
                imageDirectory = imageDirectory,
                ownerDraftId = viewModel.ownerDraftId,
                rootPath = rootDirectory,
                imageMap = stableImageMap,
            ),
            toolbarToolOrder = appPreferences.inputToolbarToolOrder,
            onUpdateMemo = viewModel::submitMemoUpdate,
            submissionState = viewModel.editorSubmissionState,
            onSaveImages = viewModel::saveImages,
            onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
        onEditorOpenFailure = viewModel::reportError,
        onAutoSaveCommitted = rememberAutoSaveCommittedNotifier(snackbarHostState),
    ) { showMenu, openEditor ->
        TagFilterScreenScaffold(
            tagName = tagName,
            snackbarHostState = snackbarHostState,
            onBackClick = {
                haptic.medium()
                onBackClick()
            },
        ) { paddingValues ->
            TagFilterScreenContent(
                tagName = tagName,
                pagedItems = pagedItems,
                doubleTapEditEnabled = appPreferences.doubleTapEditEnabled,
                freeTextCopyEnabled = appPreferences.freeTextCopyEnabled,
                exitAnimationRegistry = viewModel.exitAnimationRegistry,
                onMemoEdit = openEditor,
                onShowMenu = showMenu,
                onImageClick = onNavigateToImage,
                onTodoClick = viewModel::toggleTodo,
                onNavigateToTag = onNavigateToTag,
                modifier = modifier.padding(paddingValues),
            )
        }
    }
    com.lomo.app.feature.main.MainVersionHistoryEffectHost(
        bindings = mainViewModel.versionHistoryEffectBindings,
    )
}

@Composable
private fun TagFilterScreenEffects(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagFilterScreenScaffold(
    tagName: String,
    snackbarHostState: SnackbarHostState,
    onBackClick: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    Scaffold(
        modifier =
        Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .benchmarkAnchorRoot(BenchmarkAnchorContract.TAG_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { TagFilterTitle(tagName = tagName) },
                navigationIcon = {
                    LomoIconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        LomoIcon(
                            LomoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
private fun TagFilterTitle(tagName: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = AppShapes.Medium,
            modifier = Modifier.size(TAG_FILTER_ICON_BADGE_SIZE),
        ) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                LomoIcon(
                    LomoIcons.Tag,
                    contentDescription = null,
                    modifier = Modifier.size(TAG_FILTER_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.Small))
        Text(
            text = tagName,
            style = MaterialTheme.typography.titleLargeEmphasized,
        )
    }
}

@Composable
private fun TagFilterScreenContent(
    tagName: String,
    pagedItems: LazyPagingItems<com.lomo.app.feature.main.MemoUiModel>,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<com.lomo.app.feature.main.MemoUiModel>,
    onMemoEdit: (com.lomo.domain.model.Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onTodoClick: (com.lomo.domain.model.Memo, com.lomo.domain.model.markdown.MarkdownSourceSpan) -> Unit,
    onNavigateToTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val contentState = resolveTagFilterContentState(pagedItems.itemCount, pagedItems.loadState.refresh)
    Box(modifier = modifier.fillMaxSize()) {
        LomoStateContent(
            state = contentState,
            contentKey = { it },
            modifier = Modifier.fillMaxSize(),
        ) { state ->
            when (state) {
                TagFilterContentState.Loading ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        ExpressiveContainedLoadingIndicator()
                    }

                TagFilterContentState.Error ->
                    EmptyState(
                        icon = LomoIcons.Error,
                        title = stringResource(R.string.memo_list_load_failed_title),
                        description = stringResource(R.string.memo_list_load_failed_description),
                        action = {
                            LomoTextButton(onClick = pagedItems::retry) {
                                Text(stringResource(R.string.action_retry))
                            }
                        },
                    )

                TagFilterContentState.Empty ->
                    EmptyState(
                        icon = LomoIcons.Tag,
                        title = stringResource(R.string.empty_no_tag_matches_title, tagName),
                        description = stringResource(R.string.empty_no_tag_matches_subtitle),
                    )

                TagFilterContentState.List -> MemoCardList(
                    pagedMemos = pagedItems,
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    onMemoEdit = onMemoEdit,
                    onShowMenu = onShowMenu,
                    onImageClick = onImageClick,
                    onTodoClick = onTodoClick,
                    onTagClick = onNavigateToTag,
                    exitAnimationRegistry = exitAnimationRegistry,
                    listState = listState,
                    contentPadding =
                    PaddingValues(
                        top = TAG_FILTER_LIST_PADDING,
                        start = TAG_FILTER_LIST_PADDING,
                        end = TAG_FILTER_LIST_PADDING,
                        bottom = TAG_FILTER_LIST_BOTTOM_PADDING,
                    ),
                )
            }
        }
    }
}
