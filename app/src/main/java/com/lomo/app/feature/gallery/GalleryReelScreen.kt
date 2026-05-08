package com.lomo.app.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lomo.app.R
import com.lomo.app.feature.memo.memoMenuState
import com.lomo.ui.component.menu.MemoMenuState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

private const val GALLERY_REEL_SCROLL_LOCK_THRESHOLD = 0.01f
private const val GALLERY_REEL_MAX_ZOOM_FACTOR = 5f
private const val GALLERY_REEL_SINGLE_PAGE_THRESHOLD = 1
private const val GALLERY_REEL_PAGE_INDEX_OFFSET = 1
private val GALLERY_REEL_INDICATOR_BOTTOM_PADDING = 224.dp
private val GALLERY_REEL_INDICATOR_DOT_SIZE = 6.dp

@Composable
fun GalleryReelScreen(
    request: GalleryReelRequest,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    modifier: Modifier = Modifier,
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
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
) {
    val initialMemoIndex = request.initialMemoIndex.coerceIn(0, request.memos.lastIndex)
    val verticalPagerState =
        rememberPagerState(
            initialPage = initialMemoIndex,
            pageCount = { request.memos.size },
        )
    val overlayState =
        remember {
            AnchoredDraggableState(GalleryReelOverlayAnchor.Collapsed)
        }
    val scope = rememberCoroutineScope()
    var activeZoomFraction by remember { mutableFloatStateOf(0f) }
    var activeMemoId by rememberSaveable {
        mutableStateOf(request.memos[initialMemoIndex].memo.id)
    }
    val currentMemo = request.memos.getOrNull(verticalPagerState.currentPage)

    GalleryReelScreenEffects(
        request = request,
        verticalPagerState = verticalPagerState,
        overlayState = overlayState,
        activeMemoId = activeMemoId,
        onActiveMemoIdChanged = { activeMemoId = it },
        onActiveZoomFractionChanged = { activeZoomFraction = it },
        onBackClick = onBackClick,
    )
    GalleryReelBackHandler(
        overlayState = overlayState,
        onBackClick = onBackClick,
        animateScope = scope,
    )

    GalleryReelContent(
        request = request,
        currentMemo = currentMemo,
        initialMemoIndex = initialMemoIndex,
        verticalPagerState = verticalPagerState,
        overlayState = overlayState,
        activeZoomFraction = activeZoomFraction,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onBackClick = onBackClick,
        onShowMenu = onShowMenu,
        onActiveZoomFractionChanged = { activeZoomFraction = it },
    )
}

@Composable
private fun BoxScope.GalleryReelContent(
    request: GalleryReelRequest,
    currentMemo: com.lomo.app.feature.main.MemoUiModel?,
    initialMemoIndex: Int,
    verticalPagerState: PagerState,
    overlayState: AnchoredDraggableState<GalleryReelOverlayAnchor>,
    activeZoomFraction: Float,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
    onActiveZoomFractionChanged: (Float) -> Unit,
) {
    GalleryReelVerticalPager(
        request = request,
        initialMemoIndex = initialMemoIndex,
        verticalPagerState = verticalPagerState,
        activeZoomFraction = activeZoomFraction,
        onActiveZoomFractionChanged = onActiveZoomFractionChanged,
    )
    GalleryReelChrome(
        memo = currentMemo,
        overlayState = overlayState,
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
    onActiveZoomFractionChanged: (Float) -> Unit,
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
            onZoomFractionChanged = { zoomFraction ->
                if (page == verticalPagerState.currentPage) {
                    onActiveZoomFractionChanged(zoomFraction)
                }
            },
        )
    }
}

@Composable
private fun BoxScope.GalleryReelChrome(
    memo: com.lomo.app.feature.main.MemoUiModel?,
    overlayState: AnchoredDraggableState<GalleryReelOverlayAnchor>,
    dateFormat: String,
    timeFormat: String,
    onBackClick: () -> Unit,
    onShowMenu: (MemoMenuState) -> Unit,
) {
    memo ?: return
    val showMenu = {
        onShowMenu(
            memoMenuState(
                memo = memo.memo,
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                imageUrls = memo.imageUrls,
            ),
        )
    }
    GalleryReelMemoOverlay(
        memo = memo,
        draggableState = overlayState,
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        onShowMoreMenu = showMenu,
        modifier =
            Modifier
                .fillMaxSize()
                .zIndex(1f),
    )
    GalleryReelTopBar(
        onClose = onBackClick,
        onShowMenu = showMenu,
        modifier = Modifier.zIndex(2f),
    )
}

@Composable
private fun GalleryReelScreenEffects(
    request: GalleryReelRequest,
    verticalPagerState: PagerState,
    overlayState: AnchoredDraggableState<GalleryReelOverlayAnchor>,
    activeMemoId: String,
    onActiveMemoIdChanged: (String) -> Unit,
    onActiveZoomFractionChanged: (Float) -> Unit,
    onBackClick: () -> Unit,
) {
    LaunchedEffect(verticalPagerState, request.memos) {
        snapshotFlow { verticalPagerState.currentPage }
            .collectLatest { page ->
                onActiveZoomFractionChanged(0f)
                request.memos.getOrNull(page)?.let { onActiveMemoIdChanged(it.memo.id) }
                overlayState.animateTo(GalleryReelOverlayAnchor.Collapsed)
            }
    }

    LaunchedEffect(request.memos, activeMemoId) {
        if (request.memos.none { uiModel -> uiModel.memo.id == activeMemoId }) {
            onBackClick()
        }
    }
}

@Composable
private fun GalleryReelBackHandler(
    overlayState: AnchoredDraggableState<GalleryReelOverlayAnchor>,
    onBackClick: () -> Unit,
    animateScope: kotlinx.coroutines.CoroutineScope,
) {
    BackHandler {
        val nextAnchor = nextAnchorOnBack(overlayState.currentValue)
        val shouldPopRoute =
            nextAnchor == GalleryReelOverlayAnchor.Hidden &&
                overlayState.currentValue == GalleryReelOverlayAnchor.Hidden
        if (shouldPopRoute) {
            onBackClick()
        } else {
            animateScope.launch {
                overlayState.animateTo(nextAnchor)
            }
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
    onZoomFractionChanged: (Float) -> Unit,
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
                onZoomFractionChanged = { zoomFraction ->
                    if (imageIndex == pagerState.currentPage) {
                        activeZoomFraction = zoomFraction
                    }
                },
            )
        }

        if (imageUrls.size > GALLERY_REEL_SINGLE_PAGE_THRESHOLD) {
            GalleryReelImageDots(
                currentPage = pagerState.currentPage,
                pageCount = imageUrls.size,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = GALLERY_REEL_INDICATOR_BOTTOM_PADDING),
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

@Composable
private fun BoxScope.GalleryReelImageDots(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.36f),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.gallery_reel_image_indicator,
                        currentPage + GALLERY_REEL_PAGE_INDEX_OFFSET,
                        pageCount,
                    ),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            repeat(pageCount) { index ->
                Box(
                    modifier =
                        Modifier
                            .size(GALLERY_REEL_INDICATOR_DOT_SIZE)
                            .background(
                                color =
                                    if (index == currentPage) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.36f)
                                    },
                                shape = MaterialTheme.shapes.extraLarge,
                            ),
                )
            }
        }
    }
}
