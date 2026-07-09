package com.lomo.app.feature.tag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.util.injectedKoinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.R
import com.lomo.app.feature.image.ImageViewerRequest
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.memo.MemoCardList
import com.lomo.app.feature.memo.MemoCardListAnimation
import com.lomo.app.feature.memo.MemoEditorSessionState
import com.lomo.app.feature.memo.MemoInteractionHost
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.existingMemoEditorSurface
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberMemoEditorController
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet

import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems

private val TAG_FILTER_ICON_SIZE = 28.dp
private val TAG_FILTER_ICON_SPACING = 8.dp
private val TAG_FILTER_LIST_PADDING = 16.dp
private val TAG_FILTER_LIST_BOTTOM_PADDING = 88.dp

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

    TagFilterScreenEffects(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel::clearError,
    )
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
            presentationState =
                MemoMenuPresentationState(
                    shareCardShowTime = appPreferences.shareCardShowTime,
                    shareCardShowSignature = appPreferences.shareCardShowBrand,
                    shareCardSignatureText = appPreferences.shareCardSignatureText,
                    customFontPath = appPreferences.customFontPath,
                    showJump = true,
                    memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
                    memoActionOrder = appPreferences.memoActionOrderFor(MemoActionOrderScopes.TAG),
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
        existingMemoEditorSurface(
            session =
                MemoEditorSessionState(
                    imageDirectory = imageDirectory,
                    rootPath = rootDirectory,
                    imageMap = stableImageMap,
                    dateFormat = appPreferences.dateFormat,
                    timeFormat = appPreferences.timeFormat,
                ),
            toolbarToolOrder = appPreferences.inputToolbarToolOrder,
            onUpdateMemo = viewModel::updateMemo,
            onSaveImage = viewModel::saveImage,
            onToolbarOrderChanged = viewModel.updateInputToolbarToolOrder,
        )

    MemoInteractionHost(
        menuCommandHandler = memoMenuCommandHandler,
        controller = editorController,
        editorSurface = editorSurface,
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
                dateFormat = appPreferences.dateFormat,
                timeFormat = appPreferences.timeFormat,
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier =
            Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .benchmarkAnchorRoot(BenchmarkAnchorContract.TAG_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { TagFilterTitle(tagName = tagName) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
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
        Icon(
            Icons.Outlined.Tag,
            contentDescription = null,
            modifier = Modifier.size(TAG_FILTER_ICON_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(TAG_FILTER_ICON_SPACING))
        Text(tagName)
    }
}

@Composable
private fun TagFilterScreenContent(
    tagName: String,
    pagedItems: LazyPagingItems<com.lomo.app.feature.main.MemoUiModel>,
    dateFormat: String,
    timeFormat: String,
    doubleTapEditEnabled: Boolean,
    freeTextCopyEnabled: Boolean,
    exitAnimationRegistry: com.lomo.ui.component.common.ExitAnimationRegistry<com.lomo.app.feature.main.MemoUiModel>,
    onMemoEdit: (com.lomo.domain.model.Memo) -> Unit,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onImageClick: (ImageViewerRequest) -> Unit,
    onTodoClick: (com.lomo.domain.model.Memo, Int, Boolean) -> Unit,
    onNavigateToTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        if (pagedItems.itemCount == 0) {
            EmptyState(
                icon = Icons.Outlined.Tag,
                title = stringResource(R.string.empty_no_tag_matches_title, tagName),
                description = stringResource(R.string.empty_no_tag_matches_subtitle),
            )
        } else {
            MemoCardList(
                pagedMemos = pagedItems,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                onMemoEdit = onMemoEdit,
                onShowMenu = onShowMenu,
                onImageClick = onImageClick,
                onTodoClick = onTodoClick,
                onTagClick = onNavigateToTag,
                animation = MemoCardListAnimation.Placement,
                exitAnimationRegistry = exitAnimationRegistry,
                listState = listState,
                contentPadding =
                    PaddingValues(
                        top = TAG_FILTER_LIST_PADDING,
                        start = TAG_FILTER_LIST_PADDING,
                        end = TAG_FILTER_LIST_PADDING,
                        bottom = TAG_FILTER_LIST_BOTTOM_PADDING,
                    ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
