package com.lomo.app.feature.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import com.lomo.app.R
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.app.feature.image.lomoSharedKeyImageRequest
import com.lomo.app.feature.main.GalleryUiMemosState
import com.lomo.app.feature.main.MainViewModel
import com.lomo.app.feature.main.MemoUiModel
import com.lomo.app.feature.memo.MemoMenuBinder
import com.lomo.app.feature.memo.MemoMenuSelection
import com.lomo.app.feature.memo.MemoMenuPresentationState
import com.lomo.app.feature.memo.handleMemoJumpToMain
import com.lomo.app.feature.memo.rememberMemoMenuCommandHandler
import com.lomo.app.util.activityHiltViewModel
import com.lomo.ui.component.common.EmptyState
import com.lomo.ui.component.common.ExpressiveContainedLoadingIndicator
import com.lomo.ui.theme.AppSpacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.collections.immutable.toPersistentMap
import timber.log.Timber

private const val GALLERY_DIMENSION_PREFETCH_COUNT = 12
private const val GALLERY_LOG_TAG = "GalleryScreen"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    onBackClick: () -> Unit,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onNavigateToShare: (String, Long) -> Unit = { _, _ -> },
    onNavigateToMain: () -> Unit = onBackClick,
    lanShareEnabled: Boolean = true,
) {
    val viewModel: MainViewModel = activityHiltViewModel()
    val galleryState by viewModel.galleryUiMemosState.collectAsStateWithLifecycle()
    val memos =
        remember(galleryState) {
            when (val state = galleryState) {
                GalleryUiMemosState.Loading -> emptyList()
                is GalleryUiMemosState.Loaded -> state.memos
            }
        }
    val galleryMemos = remember(memos) { memos.toImmutableList() }
    val deletingMemoIds by viewModel.deletingMemoIds.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageLoader = context.imageLoader
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
        initialImageUrls = entryReadiness.initialImageUrls,
        onInitialImageReady = entryReadiness.onInitialImageReady,
        dimensionImageUrls = entryReadiness.dimensionImageUrls,
        context = context,
        imageLoader = imageLoader,
        dimensionResolver = dimensionResolver,
    )

    GalleryScreenMenuContent(
        onBackClick = onBackClick,
        onNavigateToShare = onNavigateToShare,
        onNavigateToMain = onNavigateToMain,
        lanShareEnabled = lanShareEnabled,
        viewModel = viewModel,
        shareCardShowTime = appPreferences.shareCardShowTime,
        shareCardShowSignature = appPreferences.shareCardShowBrand,
        shareCardSignatureText = appPreferences.shareCardSignatureText,
        customFontPath = appPreferences.customFontPath,
        memoActionAutoReorderEnabled = appPreferences.memoActionAutoReorderEnabled,
        memoActionOrder = appPreferences.memoActionOrderFor(MemoActionOrderScopes.GALLERY),
        galleryState = galleryState,
        aspectByMemoId = entryReadiness.aspectByMemoId,
        aspectsReady = entryReadiness.aspectsReady,
        initialImagesReady = entryReadiness.initialImagesReady,
        dateFormat = appPreferences.dateFormat,
        timeFormat = appPreferences.timeFormat,
        onNavigateToReel = onNavigateToReel,
        onResolveImageAspect = { imageUrl ->
            dimensionResolver.resolve(imageUrl)
        },
        haptic = haptic,
        scrollBehavior = scrollBehavior,
        snackbarHostState = snackbarHostState,
    )
}

private data class GalleryEntryReadiness(
    val aspectByMemoId: ImmutableMap<String, Float>?,
    val aspectsReady: Boolean,
    val initialImageUrls: ImmutableList<String>,
    val initialImagesReady: Boolean,
    val dimensionImageUrls: ImmutableList<String>,
    val onInitialImageReady: (String) -> Unit,
)

@Composable
private fun rememberGalleryEntryReadiness(
    memos: ImmutableList<MemoUiModel>,
    dimensionResolver: GalleryImageDimensionResolver,
): GalleryEntryReadiness {
    val aspectByPath by dimensionResolver.aspectFlow.collectAsStateWithLifecycle()
    val galleryLayoutInputs =
        remember(memos) {
            memos.mapNotNull { uiModel ->
                val firstImageUrl = uiModel.imageUrls.firstOrNull() ?: return@mapNotNull null
                galleryLayoutInput(memoId = uiModel.memo.id, firstImageUrl = firstImageUrl)
            }
        }
    val initialImageUrls = rememberGalleryInitialImageUrls(memos)
    var readyInitialImageUrls by remember(initialImageUrls) { mutableStateOf<Set<String>>(emptySet()) }
    val initialImagesReady =
        remember(initialImageUrls, readyInitialImageUrls) {
            initialImageUrls.all { imageUrl -> imageUrl in readyInitialImageUrls }
        }
    val aspectByMemoId =
        remember(galleryLayoutInputs, aspectByPath) {
            resolveGalleryAspectByMemoIdOrNull(
                layoutInputs = galleryLayoutInputs,
                aspectByImageUrl = aspectByPath,
            )?.toPersistentMap()
        }
    val dimensionImageUrls =
        remember(galleryLayoutInputs) {
            galleryLayoutInputs
                .asSequence()
                .map { input -> input.firstImageUrl }
                .distinct()
                .toList()
                .toImmutableList()
        }
    // Every gallery tile's aspect ratio must be known before the mosaic is laid out, otherwise tiles
    // start square and visibly reshuffle as ratios decode. Decoded ratios are cached, so this only
    // gates the first (cold-cache) visit.
    val aspectsReady =
        remember(dimensionImageUrls, aspectByPath) {
            dimensionImageUrls.all { imageUrl -> imageUrl in aspectByPath }
        }
    return GalleryEntryReadiness(
        aspectByMemoId = aspectByMemoId,
        aspectsReady = aspectsReady,
        initialImageUrls = initialImageUrls,
        initialImagesReady = initialImagesReady,
        dimensionImageUrls = dimensionImageUrls,
        onInitialImageReady = { imageUrl ->
            readyInitialImageUrls = readyInitialImageUrls + imageUrl
        },
    )
}

@Composable
private fun rememberGalleryInitialImageUrls(memos: ImmutableList<MemoUiModel>): ImmutableList<String> =
    remember(memos) {
        memos
            .asSequence()
            .mapNotNull { uiModel -> uiModel.imageUrls.firstOrNull() }
            .distinct()
            .take(GALLERY_DIMENSION_PREFETCH_COUNT)
            .toList()
            .toImmutableList()
    }

@Composable
private fun GalleryScreenMenuContent(
    onBackClick: () -> Unit,
    onNavigateToShare: (String, Long) -> Unit,
    onNavigateToMain: () -> Unit,
    lanShareEnabled: Boolean,
    viewModel: MainViewModel,
    shareCardShowTime: Boolean,
    shareCardShowSignature: Boolean,
    shareCardSignatureText: String,
    customFontPath: String?,
    memoActionAutoReorderEnabled: Boolean,
    memoActionOrder: ImmutableList<String>,
    galleryState: GalleryUiMemosState,
    aspectByMemoId: ImmutableMap<String, Float>?,
    aspectsReady: Boolean,
    initialImagesReady: Boolean,
    dateFormat: String,
    timeFormat: String,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onResolveImageAspect: suspend (String) -> Unit,
    haptic: com.lomo.ui.util.AppHapticFeedback,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    snackbarHostState: SnackbarHostState,
) {
    val memoMenuCommandHandler =
        rememberMemoMenuCommandHandler(
            presentationState =
                MemoMenuPresentationState(
                    shareCardShowTime = shareCardShowTime,
                    shareCardShowSignature = shareCardShowSignature,
                    shareCardSignatureText = shareCardSignatureText,
                    customFontPath = customFontPath,
                    showJump = true,
                    memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
                    memoActionOrder = memoActionOrder,
                ),
            onEditMemo = { memo ->
                viewModel.requestOpenMemo(memo.id)
                onBackClick()
            },
            onDeleteMemo = viewModel.deleteMemo,
            onLanShare =
                if (lanShareEnabled) {
                    { request -> onNavigateToShare(request.content, request.timestamp) }
                } else {
                    null
                },
            onJump = { state ->
                handleMemoJumpToMain(
                    selection = state,
                    requestFocusMemo = viewModel.requestFocusMemoInDefaultMainList,
                    navigateToMain = onNavigateToMain,
                )
            },
            onMemoActionInvoked = viewModel.recordGalleryMemoActionUsage,
            onMemoActionOrderChanged = viewModel.updateGalleryMemoActionOrder,
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
                aspectByMemoId = aspectByMemoId,
                aspectsReady = aspectsReady,
                initialImagesReady = initialImagesReady,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                padding = padding,
                onShowMenu = showMenu,
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
    memos: ImmutableList<MemoUiModel>,
    deletingMemoIds: ImmutableSet<String>,
    onDeleteAnimationSettled: (String) -> Unit,
    initialImageUrls: ImmutableList<String>,
    onInitialImageReady: (String) -> Unit,
    dimensionImageUrls: ImmutableList<String>,
    context: android.content.Context,
    imageLoader: ImageLoader,
    dimensionResolver: GalleryImageDimensionResolver,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onClearError()
        }
    }
    LaunchedEffect(initialImageUrls, context, imageLoader) {
        initialImageUrls.forEach { imageUrl ->
            preloadGalleryInitialImage(
                context = context,
                imageLoader = imageLoader,
                imageUrl = imageUrl,
            )
            onInitialImageReady(imageUrl)
        }
    }
    LaunchedEffect(dimensionImageUrls, dimensionResolver) {
        // Resolve every tile's aspect ratio up front (cheap bounds decode, cached) so the mosaic is
        // laid out once at its final shape; see resolveGalleryScreenDisplayState gating.
        dimensionImageUrls.forEach { imageUrl ->
            dimensionResolver.resolve(imageUrl)
        }
    }
    LaunchedEffect(memos, deletingMemoIds) {
        val visibleMemoIds = memos.asSequence().map { uiModel -> uiModel.memo.id }.toSet()
        deletingMemoIds
            .asSequence()
            .filterNot { memoId -> memoId in visibleMemoIds }
            .forEach(onDeleteAnimationSettled)
    }
}

private suspend fun preloadGalleryInitialImage(
    context: android.content.Context,
    imageLoader: ImageLoader,
    imageUrl: String,
) {
    val result = imageLoader.execute(lomoSharedKeyImageRequest(context = context, url = imageUrl))
    if (result is ErrorResult) {
        Timber
            .tag(GALLERY_LOG_TAG)
            .w(result.throwable, "Failed to preload initial gallery image: %s", imageUrl)
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.medium()
                            onBackClick()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
    aspectByMemoId: ImmutableMap<String, Float>?,
    aspectsReady: Boolean,
    initialImagesReady: Boolean,
    dateFormat: String,
    timeFormat: String,
    padding: PaddingValues,
    onShowMenu: (MemoMenuSelection) -> Unit,
    onNavigateToReel: (memoId: String, imageIndex: Int, aspectByMemoId: Map<String, Float>) -> Unit,
    onResolveImageAspect: suspend (String) -> Unit,
) {
    when (
        val displayState =
            resolveGalleryScreenDisplayState(
                galleryState = galleryState,
                aspectByMemoId = aspectByMemoId,
                aspectsReady = aspectsReady,
                initialImagesReady = initialImagesReady,
            )
    ) {
        GalleryScreenDisplayState.Loading -> GalleryLoadingState(padding = padding)
        GalleryScreenDisplayState.Empty -> GalleryEmptyState(padding = padding)
        is GalleryScreenDisplayState.Grid ->
            GalleryGridContent(
                memos = displayState.memos,
                aspectByMemoId = displayState.aspectByMemoId,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
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
            icon = Icons.Outlined.PhotoLibrary,
            title = stringResource(R.string.gallery_empty_title),
            description = stringResource(R.string.gallery_empty_desc),
        )
    }
}
