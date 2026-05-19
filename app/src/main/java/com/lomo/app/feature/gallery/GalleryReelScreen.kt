package com.lomo.app.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lomo.app.R
import com.lomo.app.feature.image.lomoSharedKeyImageRequest
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.ui.component.image.RetainedAsyncImage
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.coroutines.flow.collectLatest
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

private const val GALLERY_REEL_SCROLL_LOCK_THRESHOLD = 0.01f
private const val GALLERY_REEL_MAX_ZOOM_FACTOR = 5f
private const val GALLERY_REEL_SINGLE_PAGE_THRESHOLD = 1
private const val GALLERY_REEL_PAGE_INDEX_OFFSET = 1

private data class GalleryReelActiveImageState(
    val memoId: String,
    val currentPage: Int,
    val pageCount: Int,
)

@Composable
fun GalleryReelScreen(
    request: GalleryReelRequest,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewerMode: GalleryReelMode = GalleryReelMode.Gallery,
    memoChromeEnabled: Boolean = true,
    onShowMenu: ((MemoMenuState) -> Unit)? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        if (request.memos.isEmpty()) {
            LaunchedEffect(Unit) {
                onBackClick()
            }
        } else {
            GalleryReelLoadedRoute(
                request = request,
                viewerMode = viewerMode,
                memoChromeEnabled = memoChromeEnabled,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                onBackClick = onBackClick,
                onShowMenu = onShowMenu,
            )
        }
    }
}

@Composable
private fun BoxScope.GalleryReelLoadedRoute(
    request: GalleryReelRequest,
    viewerMode: GalleryReelMode,
    memoChromeEnabled: Boolean,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: ((MemoMenuState) -> Unit)?,
) {
    GalleryReelImmersiveSystemBars()

    val initialMemoIndex = request.initialMemoIndex.coerceIn(0, request.memos.lastIndex)
    val verticalPagerState =
        rememberPagerState(
            initialPage = initialMemoIndex,
            pageCount = { request.memos.size },
        )
    var chromeVisibility by rememberSaveable {
        mutableStateOf(GalleryReelChromeVisibility.Visible)
    }
    var activeZoomFraction by remember { mutableFloatStateOf(0f) }
    var activeMemoId by rememberSaveable {
        mutableStateOf(request.memos[initialMemoIndex].memo.id)
    }
    var activeImageIndicatorState by remember(request.memos, initialMemoIndex, request.initialImageIndex) {
        val initialMemo = request.memos.getOrNull(initialMemoIndex)
        val initialImageCount = initialMemo?.imageUrls?.size ?: 0
        mutableStateOf(
            if (initialMemo != null && initialImageCount > GALLERY_REEL_SINGLE_PAGE_THRESHOLD) {
                GalleryReelActiveImageState(
                    memoId = initialMemo.memo.id,
                    currentPage =
                        request.initialImageIndex.coerceIn(
                            0,
                            initialImageCount - GALLERY_REEL_PAGE_INDEX_OFFSET,
                        ),
                    pageCount = initialImageCount,
                )
            } else {
                null
            },
        )
    }
    var activeImageIndex by rememberSaveable { mutableIntStateOf(request.initialImageIndex) }
    val currentMemo = request.memos.getOrNull(verticalPagerState.currentPage)
    val activeImageUrl = currentMemo?.imageUrls?.getOrNull(activeImageIndex)
    val context = LocalContext.current

    if (activeImageUrl != null) {
        val blurModel =
            remember(activeImageUrl, context) {
                lomoSharedKeyImageRequest(context = context, url = activeImageUrl)
            }
        RetainedAsyncImage(
            model = blurModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(radius = 72.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f)),
        )
    }

    GalleryReelScreenEffects(
        request = request,
        viewerMode = viewerMode,
        verticalPagerState = verticalPagerState,
        chromeVisibility = chromeVisibility,
        activeMemoId = activeMemoId,
        onActiveMemoIdChanged = { activeMemoId = it },
        onChromeVisibilityChanged = { chromeVisibility = it },
        onActiveZoomFractionChanged = { activeZoomFraction = it },
        onBackClick = onBackClick,
    )
    GalleryReelBackHandler(
        chromeVisibility = chromeVisibility,
        onBackClick = onBackClick,
    )

    GalleryReelContent(
        request = request,
        currentMemo = currentMemo,
        initialMemoIndex = initialMemoIndex,
        verticalPagerState = verticalPagerState,
        viewerMode = viewerMode,
        memoChromeEnabled = memoChromeEnabled,
        chromeVisibility = chromeVisibility,
        activeZoomFraction = activeZoomFraction,
        activeImageIndicatorState = activeImageIndicatorState,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onBackClick = onBackClick,
        onShowMenu = onShowMenu,
        onToggleChrome = { chromeVisibility = toggleGalleryReelChromeVisibility(chromeVisibility) },
        onActiveZoomFractionChanged = { activeZoomFraction = it },
        onActiveImagePageChanged = { memoId, currentPage, pageCount ->
            activeImageIndex = currentPage
            activeImageIndicatorState =
                GalleryReelActiveImageState(
                    memoId = memoId,
                    currentPage = currentPage,
                    pageCount = pageCount,
                ).takeIf { pageCount > GALLERY_REEL_SINGLE_PAGE_THRESHOLD }
        },
    )
}

@Composable
private fun BoxScope.GalleryReelContent(
    request: GalleryReelRequest,
    currentMemo: com.lomo.app.feature.main.MemoUiModel?,
    initialMemoIndex: Int,
    verticalPagerState: PagerState,
    viewerMode: GalleryReelMode,
    memoChromeEnabled: Boolean,
    chromeVisibility: GalleryReelChromeVisibility,
    activeZoomFraction: Float,
    activeImageIndicatorState: GalleryReelActiveImageState?,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: ((MemoMenuState) -> Unit)?,
    onToggleChrome: () -> Unit,
    onActiveZoomFractionChanged: (Float) -> Unit,
    onActiveImagePageChanged: (memoId: String, currentPage: Int, pageCount: Int) -> Unit,
) {
    if (viewerMode.allowsMemoPaging) {
        GalleryReelVerticalPager(
            request = request,
            initialMemoIndex = initialMemoIndex,
            verticalPagerState = verticalPagerState,
            activeZoomFraction = activeZoomFraction,
            onImageClick = onToggleChrome,
            onActiveZoomFractionChanged = onActiveZoomFractionChanged,
            onActiveImagePageChanged = onActiveImagePageChanged,
        )
    } else {
        GalleryReelSingleMemoPage(
            memo = currentMemo,
            initialMemoIndex = initialMemoIndex,
            initialImageIndex = request.initialImageIndex,
            onImageClick = onToggleChrome,
            onActiveZoomFractionChanged = onActiveZoomFractionChanged,
            onActiveImagePageChanged = onActiveImagePageChanged,
        )
    }
    GalleryReelChrome(
        memo = currentMemo,
        memoChromeEnabled = memoChromeEnabled,
        chromeVisibility = chromeVisibility,
        imageIndicatorState = activeImageIndicatorState,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onBackClick = onBackClick,
        onShowMenu = onShowMenu,
    )
}

@Composable
private fun GalleryReelVerticalPager(
    request: GalleryReelRequest,
    initialMemoIndex: Int,
    verticalPagerState: PagerState,
    activeZoomFraction: Float,
    onImageClick: () -> Unit,
    onActiveZoomFractionChanged: (Float) -> Unit,
    onActiveImagePageChanged: (memoId: String, currentPage: Int, pageCount: Int) -> Unit,
) {
    VerticalPager(
        state = verticalPagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = GALLERY_REEL_SINGLE_PAGE_THRESHOLD,
        userScrollEnabled = activeZoomFraction <= GALLERY_REEL_SCROLL_LOCK_THRESHOLD,
    ) { page ->
        val memo = request.memos[page]
        GalleryReelPage(
            memo = memo,
            memoIndex = page,
            initialMemoIndex = initialMemoIndex,
            initialImageIndex = request.initialImageIndex,
            isActive = page == verticalPagerState.currentPage,
            onImageClick = onImageClick,
            onZoomFractionChanged = { zoomFraction ->
                if (page == verticalPagerState.currentPage) {
                    onActiveZoomFractionChanged(zoomFraction)
                }
            },
            onImagePageChanged = onActiveImagePageChanged,
        )
    }
}

@Composable
private fun GalleryReelSingleMemoPage(
    memo: com.lomo.app.feature.main.MemoUiModel?,
    initialMemoIndex: Int,
    initialImageIndex: Int,
    onImageClick: () -> Unit,
    onActiveZoomFractionChanged: (Float) -> Unit,
    onActiveImagePageChanged: (memoId: String, currentPage: Int, pageCount: Int) -> Unit,
) {
    memo ?: return
    GalleryReelPage(
        memo = memo,
        memoIndex = initialMemoIndex,
        initialMemoIndex = initialMemoIndex,
        initialImageIndex = initialImageIndex,
        isActive = true,
        onImageClick = onImageClick,
        onZoomFractionChanged = onActiveZoomFractionChanged,
        onImagePageChanged = onActiveImagePageChanged,
    )
}

@Composable
private fun BoxScope.GalleryReelChrome(
    memo: com.lomo.app.feature.main.MemoUiModel?,
    memoChromeEnabled: Boolean,
    chromeVisibility: GalleryReelChromeVisibility,
    imageIndicatorState: GalleryReelActiveImageState?,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: ((MemoMenuState) -> Unit)?,
) {
    memo ?: return
    val showMenu =
        onShowMenu?.let { show ->
            {
                show(
                    memoMenuState(
                        memo = memo.memo,
                        dateFormat = dateFormat,
                        timeFormat = timeFormat,
                        imageUrls = memo.imageUrls,
                    ),
                )
            }
        }
    val visibleImageIndicatorState =
        imageIndicatorState?.takeIf { indicatorState ->
            indicatorState.memoId == memo.memo.id &&
                indicatorState.pageCount > GALLERY_REEL_SINGLE_PAGE_THRESHOLD
        }
    val indicatorViewState =
        visibleImageIndicatorState?.let {
            GalleryReelImageIndicatorState(
                currentPage = it.currentPage,
                pageCount = it.pageCount,
            )
        }

    androidx.compose.animation.AnimatedVisibility(
        visible = chromeVisibility == GalleryReelChromeVisibility.Visible,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (memoChromeEnabled || indicatorViewState != null) {
                GalleryReelMemoOverlay(
                    memo = memo,
                    dateFormat = dateFormat,
                    timeFormat = timeFormat,
                    imageIndicator = indicatorViewState,
                    showMemoDetails = memoChromeEnabled,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zIndex(1f),
                )
            }
            GalleryReelTopBar(
                onClose = onBackClick,
                onShowMenu = showMenu,
                modifier = Modifier.zIndex(2f),
            )
        }
    }
}

@Composable
private fun GalleryReelScreenEffects(
    request: GalleryReelRequest,
    viewerMode: GalleryReelMode,
    verticalPagerState: PagerState,
    chromeVisibility: GalleryReelChromeVisibility,
    activeMemoId: String,
    onActiveMemoIdChanged: (String) -> Unit,
    onChromeVisibilityChanged: (GalleryReelChromeVisibility) -> Unit,
    onActiveZoomFractionChanged: (Float) -> Unit,
    onBackClick: () -> Unit,
) {
    val latestChromeVisibility by rememberUpdatedState(chromeVisibility)

    LaunchedEffect(verticalPagerState, request.memos) {
        snapshotFlow { verticalPagerState.currentPage }
            .collectLatest { page ->
                onActiveZoomFractionChanged(0f)
                request.memos.getOrNull(page)?.let { onActiveMemoIdChanged(it.memo.id) }
                onChromeVisibilityChanged(nextChromeVisibilityOnPageChange(latestChromeVisibility))
            }
    }

    LaunchedEffect(request.memos, viewerMode, activeMemoId) {
        when (
            val resolution =
                resolveGalleryReelActiveMemo(
                    viewerMode = viewerMode,
                    activeMemoId = activeMemoId,
                    requestMemoIds = request.memos.map { uiModel -> uiModel.memo.id },
                )
        ) {
            GalleryReelActiveMemoResolution.KeepActiveMemo -> Unit
            GalleryReelActiveMemoResolution.PopRoute -> onBackClick()
            is GalleryReelActiveMemoResolution.UpdateActiveMemo -> {
                onActiveMemoIdChanged(resolution.memoId)
            }
        }
    }
}

@Composable
private fun GalleryReelBackHandler(
    chromeVisibility: GalleryReelChromeVisibility,
    onBackClick: () -> Unit,
) {
    BackHandler {
        when (resolveGalleryReelBackAction(chromeVisibility)) {
            GalleryReelBackAction.PopRoute -> onBackClick()
        }
    }
}

@Composable
private fun GalleryReelPage(
    memo: com.lomo.app.feature.main.MemoUiModel,
    memoIndex: Int,
    initialMemoIndex: Int,
    initialImageIndex: Int,
    isActive: Boolean,
    onImageClick: () -> Unit,
    onZoomFractionChanged: (Float) -> Unit,
    onImagePageChanged: (memoId: String, currentPage: Int, pageCount: Int) -> Unit,
) {
    val imageUrls = memo.imageUrls
    if (imageUrls.isEmpty()) return

    val effectiveInitialImageIndex =
        if (memoIndex == initialMemoIndex) {
            initialImageIndex.coerceIn(0, imageUrls.lastIndex)
        } else {
            0
        }
    val pagerState =
        rememberPagerState(
            initialPage = effectiveInitialImageIndex,
            pageCount = { imageUrls.size },
        )
    var initialPageConsumed by rememberSaveable(memo.memo.id) {
        mutableStateOf(false)
    }
    var activeZoomFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isActive, memo.memo.id) {
        if (isActive) {
            val targetPage =
                if (!initialPageConsumed && memoIndex == initialMemoIndex) {
                    effectiveInitialImageIndex
                } else {
                    0
                }
            pagerState.scrollToPage(targetPage)
            initialPageConsumed = true
        }
    }
    LaunchedEffect(isActive, activeZoomFraction) {
        if (isActive) onZoomFractionChanged(activeZoomFraction)
    }
    LaunchedEffect(isActive, pagerState, imageUrls.size) {
        if (isActive) {
            snapshotFlow { pagerState.currentPage }
                .collectLatest { currentPage ->
                    onImagePageChanged(memo.memo.id, currentPage, imageUrls.size)
                }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled =
                imageUrls.size > GALLERY_REEL_SINGLE_PAGE_THRESHOLD &&
                    activeZoomFraction <= GALLERY_REEL_SCROLL_LOCK_THRESHOLD,
        ) { imageIndex ->
            GalleryReelImage(
                memoId = memo.memo.id,
                imageUrl = imageUrls[imageIndex],
                imageIndex = imageIndex,
                isInitiallyShared = memoIndex == initialMemoIndex && imageIndex == effectiveInitialImageIndex,
                onClick = onImageClick,
                onZoomFractionChanged = { zoomFraction ->
                    if (imageIndex == pagerState.currentPage) {
                        activeZoomFraction = zoomFraction
                    }
                },
            )
        }
    }
}

@Composable
private fun GalleryReelImage(
    memoId: String,
    imageUrl: String,
    imageIndex: Int,
    isInitiallyShared: Boolean,
    onClick: () -> Unit,
    onZoomFractionChanged: (Float) -> Unit,
) {
    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = GALLERY_REEL_MAX_ZOOM_FACTOR))
    val zoomableImageState = rememberZoomableImageState(zoomableState)
    val zoomFraction = zoomableState.zoomFraction ?: 0f
    val canPanImage = zoomFraction > GALLERY_REEL_SCROLL_LOCK_THRESHOLD

    LaunchedEffect(zoomFraction) {
        onZoomFractionChanged(zoomFraction)
    }

    ZoomableAsyncImage(
        model = imageUrl,
        contentDescription = stringResource(R.string.cd_image_viewer_fullscreen),
        gestures = EnabledZoomGestures(zoom = true, pan = canPanImage),
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (isInitiallyShared) {
                        Modifier.rememberGalleryReelSharedElementModifier(
                            memoId = memoId,
                            imageIndex = imageIndex,
                        )
                    } else {
                        Modifier
                    },
                ),
        state = zoomableImageState,
        contentScale = ContentScale.Fit,
        onClick = { onClick() },
    )
}

@Composable
private fun Modifier.rememberGalleryReelSharedElementModifier(
    memoId: String,
    imageIndex: Int,
): Modifier {
    val sharedTransitionScope = com.lomo.ui.util.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.lomo.ui.util.LocalAnimatedVisibilityScope.current

    @OptIn(ExperimentalSharedTransitionApi::class)
    return if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            sharedElement(
                rememberSharedContentState(key = gallerySharedElementKey(memoId, imageIndex)),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        this
    }
}
