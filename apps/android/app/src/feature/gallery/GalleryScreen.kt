package com.lomo.app.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.lomo.app.R
import com.lomo.app.benchmark.BenchmarkAnchorContract
import com.lomo.app.feature.main.GalleryUiMemosState
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.MemoMenuContextAction
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.memoMenuSelection
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.util.activityKoinViewModel
import com.lomo.ui.benchmark.benchmarkAnchorRoot
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBackClick: () -> Unit,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onNavigateToMain: () -> Unit = onBackClick,
    lanShareEnabled: Boolean = true,
) {
    val viewModel: MainViewModel = activityKoinViewModel()
    val galleryItems = viewModel.galleryPagedUiItems.collectAsLazyPagingItems()
    val galleryState =
        remember(galleryItems.loadState, galleryItems.itemSnapshotList.items) {
            when {
                galleryItems.loadState.refresh is LoadState.Loading && galleryItems.itemCount == 0 ->
                    GalleryUiMemosState.Loading

                galleryItems.itemCount == 0 && galleryItems.loadState.refresh is LoadState.NotLoading ->
                    GalleryUiMemosState.Loaded(emptyList())

                else -> GalleryUiMemosState.Loaded(galleryItems.itemSnapshotList.items)
            }
        }
    val memos =
        remember(galleryState) {
            when (val state = galleryState) {
                GalleryUiMemosState.Loading -> emptyList()
                is GalleryUiMemosState.Loaded -> state.memos
            }
        }
    val galleryMemos = memos
    val deletingMemoIds by viewModel.deletingMemoIds.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dimensionResolver = remember(context) { GalleryImageDimensionResolver(context.contentResolver) }
    val entryReadiness = rememberGalleryEntryReadiness(galleryMemos, dimensionResolver)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = com.lomo.ui.util.LocalAppHapticFeedback.current

    GalleryScreenEffects(
        errorMessage = errorMessage,
        snackbarHostState = snackbarHostState,
        onClearError = viewModel.clearError,
        memos = galleryMemos,
        deletingMemoIds = remember(deletingMemoIds) { deletingMemoIds.toImmutableSet() },
        onDeleteAnimationSettled = viewModel::onPagedDeleteAnimationSettled,
        dimensionImageUrls = entryReadiness.dimensionImageUrls,
        dimensionResolver = dimensionResolver,
    )

    GalleryScreenMenuContent(
        onBackClick = onBackClick,
        onNavigateToShare = onNavigateToShare,
        onNavigateToMain = onNavigateToMain,
        lanShareEnabled = lanShareEnabled,
        onOpenMemo = viewModel.requestOpenMemo,
        onDeleteMemo = viewModel.deleteMemo,
        onRequestFocusMemo = viewModel.requestFocusMemoInDefaultMainList,
        onLoadVersionHistory = { selection -> viewModel.loadVersionHistory(selection.memo) },
        onResolveMemoById = viewModel.resolveMemoById,
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        customFontPath = appPreferences.customFontPath,
        galleryState = galleryState,
        onItemAccess = { index -> galleryItems[index] },
        aspectByMemoId = entryReadiness.aspectByMemoId,
        aspectsReady = entryReadiness.aspectsReady,
        onNavigateToReel = onNavigateToReel,
        onResolveImageAspect = { imageUrl ->
            dimensionResolver.resolve(imageUrl)
        },
        haptic = haptic,
        scrollBehavior = scrollBehavior,
        snackbarHostState = snackbarHostState,
    )
    com.lomo.app.feature.main.MainVersionHistoryEffectHost(
        bindings = viewModel.versionHistoryEffectBindings,
    )
}

private data class GalleryEntryReadiness(
    val aspectByMemoId: ImmutableMap<String, Float>?,
    val aspectsReady: Boolean,
    val dimensionImageUrls: ImmutableList<String>,
)

@Composable
private fun rememberGalleryEntryReadiness(
    memos: List<GalleryUiItem>,
    dimensionResolver: GalleryImageDimensionResolver,
): GalleryEntryReadiness {
    val aspectByPath by dimensionResolver.aspectFlow.collectAsStateWithLifecycle()
    val galleryLayoutInputs =
        remember(memos) {
            memos.mapNotNull { item ->
                val firstImageUrl = item.imageUrls.firstOrNull() ?: return@mapNotNull null
                galleryLayoutInput(memoId = item.memoId, firstImageUrl = firstImageUrl)
            }
        }
    // The entry gate only waits on the leading window's aspects: bounding the wait to the first
    // tiles keeps entry work constant as paging loads grow, and the grid's viewport preheater
    // resolves later bands while they are still offscreen.
    val dimensionImageUrls =
        remember(galleryLayoutInputs) {
            galleryEntryWindowUrls(galleryLayoutInputs).toImmutableList()
        }
    var entryGeometry by remember { mutableStateOf<GalleryEntryGeometry>(GalleryEntryGeometry.Resolving) }
    LaunchedEffect(dimensionImageUrls, aspectByPath) {
        entryGeometry = resolveGalleryEntryGeometry(entryGeometry, dimensionImageUrls, aspectByPath)
    }
    val resolvedAspects = when (val geometry = entryGeometry) {
        GalleryEntryGeometry.Resolving -> aspectByPath
        is GalleryEntryGeometry.Ready -> geometry.aspectByImageUrl + aspectByPath
    }
    val aspectByMemoId = remember(galleryLayoutInputs, resolvedAspects) {
        resolveGalleryAspectByMemoIdOrNull(galleryLayoutInputs, resolvedAspects)?.toPersistentMap()
    }
    return GalleryEntryReadiness(
        aspectByMemoId = aspectByMemoId,
        aspectsReady = entryGeometry is GalleryEntryGeometry.Ready,
        dimensionImageUrls = dimensionImageUrls,
    )
}

@Composable
private fun GalleryScreenMenuContent(
    onBackClick: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit,
    onNavigateToMain: () -> Unit,
    lanShareEnabled: Boolean,
    onOpenMemo: (String) -> Unit,
    onDeleteMemo: (com.lomo.domain.model.Memo, String?) -> Unit,
    onRequestFocusMemo: (String) -> Unit,
    onLoadVersionHistory: (MemoMenuSelection) -> Unit,
    onResolveMemoById: suspend (String) -> com.lomo.domain.model.Memo?,
    shareCardShowTime: Boolean,
    shareCardShowSignature: Boolean,
    shareCardSignatureText: String,
    customFontPath: String?,
    galleryState: GalleryUiMemosState,
    onItemAccess: (Int) -> Unit,
    aspectByMemoId: ImmutableMap<String, Float>?,
    aspectsReady: Boolean,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onResolveImageAspect: suspend (String) -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
            presentationState =
            MemoMenuPresentationState(
                shareCardShowTime = shareCardShowTime,
                shareCardShowSignature = shareCardShowSignature,
                shareCardSignatureText = shareCardSignatureText,
                customFontPath = customFontPath,
            ),
            onEditMemo = { memoId ->
                onOpenMemo(memoId)
                onBackClick()
            },
            onDeleteMemo = onDeleteMemo,
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
            onVersionHistory = onLoadVersionHistory,
        )

    MemoMenuBinder(
        commandHandler = memoMenuCommandHandler,
    ) { showMenu ->
        GalleryScreenScaffold(
            onBackClick = onBackClick,
            haptic = haptic,
            scrollBehavior = scrollBehavior,
            snackbarHostState = snackbarHostState,
        ) { padding ->
            GalleryScreenContent(
                galleryState = galleryState,
                onItemAccess = onItemAccess,
                aspectByMemoId = aspectByMemoId,
                aspectsReady = aspectsReady,
                padding = padding,
                onShowMenu = { item ->
                    scope.launch {
                        val resolved = onResolveMemoById(item.memoId) ?: return@launch
                        showMenu(
                            memoMenuSelection(
                                memo = resolved,
                                imageUrls = item.imageUrls,
                            ),
                        )
                    }
                },
                onNavigateToReel = onNavigateToReel,
                onResolveImageAspect = onResolveImageAspect,
            )
        }
    }
}

@Composable
private fun GalleryScreenEffects(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onClearError: () -> Unit,
    memos: List<GalleryUiItem>,
    deletingMemoIds: ImmutableSet<String>,
    onDeleteAnimationSettled: (String) -> Unit,
    dimensionImageUrls: ImmutableList<String>,
    dimensionResolver: GalleryImageDimensionResolver,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
    LaunchedEffect(dimensionImageUrls, dimensionResolver) {
        // Resolve the entry window's aspects up front (cheap bounds decode, cached) so the mosaic
        // mounts at its final leading shape; the grid's viewport preheater resolves later bands
        // incrementally once the list is laid out.
        dimensionImageUrls.forEach { imageUrl ->
            dimensionResolver.resolve(imageUrl)
        }
    }
    LaunchedEffect(memos, deletingMemoIds) {
        val visibleMemoIds = memos.asSequence().map { item -> item.memoId }.toSet()
        deletingMemoIds
            .asSequence()
            .filterNot { memoId -> memoId in visibleMemoIds }
            .forEach(onDeleteAnimationSettled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreenScaffold(
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
            .benchmarkAnchorRoot(BenchmarkAnchorContract.GALLERY_ROOT),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.gallery_title),
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
                        LomoIcon(
                            icon = LomoIcons.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
private fun GalleryScreenContent(
    galleryState: GalleryUiMemosState,
    onItemAccess: (Int) -> Unit,
    aspectByMemoId: ImmutableMap<String, Float>?,
    aspectsReady: Boolean,
    padding: PaddingValues,
    onShowMenu: (GalleryUiItem) -> Unit,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onResolveImageAspect: suspend (String) -> Unit,
) {
    val displayState =
        resolveGalleryScreenDisplayState(
            galleryState = galleryState,
            aspectByMemoId = aspectByMemoId,
            aspectsReady = aspectsReady,
        )
    when (displayState) {
        GalleryScreenDisplayState.Loading -> GalleryLoadingState(padding = padding)

        GalleryScreenDisplayState.Empty -> GalleryEmptyState(padding = padding)

        is GalleryScreenDisplayState.Grid ->
            GalleryGridContent(
                memos = displayState.memos,
                onItemAccess = onItemAccess,
                aspectByMemoId = displayState.aspectByMemoId,
                onShowMenu = onShowMenu,
                contentPadding =
                PaddingValues(
                    top = padding.calculateTopPadding() + AppSpacing.Medium,
                    start = AppSpacing.Medium,
                    end = AppSpacing.Medium,
                    bottom = AppSpacing.Medium,
                ),
                onCellClick = { memoId, imageIndex ->
                    onNavigateToReel(memoId, imageIndex, displayState.aspectByMemoId)
                },
                onResolveImageAspect = onResolveImageAspect,
            )
    }
}

@Composable
private fun GalleryLoadingState(padding: PaddingValues) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveContainedLoadingIndicator()
    }
}

@Composable
private fun GalleryEmptyState(padding: PaddingValues) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        EmptyState(
            icon = LomoIcons.PhotoLibrary,
            title = stringResource(R.string.gallery_empty_title),
            description = stringResource(R.string.gallery_empty_desc),
        )
    }
}
