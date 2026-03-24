package com.lomo.app.feature.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

private const val IMAGE_VIEWER_SCROLL_LOCK_THRESHOLD = 0.01f
private const val IMAGE_VIEWER_MAX_ZOOM_FACTOR = 5f
private const val IMAGE_VIEWER_SINGLE_PAGE_THRESHOLD = 1
private const val IMAGE_VIEWER_PAGE_INDEX_OFFSET = 1
private const val IMAGE_VIEWER_PAGE_INDICATOR_ALPHA = 0.92f
private val IMAGE_VIEWER_PAGE_INDICATOR_BOTTOM_PADDING = 20.dp
private val IMAGE_VIEWER_CLOSE_BUTTON_PADDING = 16.dp

private data class ImageViewerContentState(
    val urls: List<String>,
    val initialIndex: Int,
)

@Composable
fun ImageViewerScreen(
    imageUrls: List<String>,
    initialIndex: Int,
    onBackClick: () -> Unit,
) {
    val contentState = rememberImageViewerContentState(imageUrls, initialIndex)
    val zoomFractions = remember(contentState.urls) { mutableStateMapOf<Int, Float>() }
    val sharedModifier =
        rememberImageViewerSharedModifier(
            urls = contentState.urls,
            initialIndex = contentState.initialIndex,
        )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        if (contentState.urls.isNotEmpty()) {
            ImageViewerPager(
                contentState = contentState,
                zoomFractions = zoomFractions,
                sharedModifier = sharedModifier,
                onBackClick = onBackClick,
            )
        }
        ImageViewerCloseButton(onBackClick = onBackClick)
    }
}

@Composable
private fun rememberImageViewerContentState(
    imageUrls: List<String>,
    initialIndex: Int,
): ImageViewerContentState {
    val normalizedUrls =
        remember(imageUrls) {
            imageUrls
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        }
    val effectiveInitialIndex =
        if (normalizedUrls.isEmpty()) {
            0
        } else {
            initialIndex.coerceIn(0, normalizedUrls.lastIndex)
        }

    return ImageViewerContentState(
        urls = normalizedUrls,
        initialIndex = effectiveInitialIndex,
    )
}

@Composable
private fun rememberImageViewerSharedModifier(
    urls: List<String>,
    initialIndex: Int,
): Modifier {
    val sharedTransitionScope = com.lomo.ui.util.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.lomo.ui.util.LocalAnimatedVisibilityScope.current

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    return if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = urls.getOrNull(initialIndex).orEmpty()),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }
}

@Composable
private fun BoxScope.ImageViewerPager(
    contentState: ImageViewerContentState,
    zoomFractions: MutableMap<Int, Float>,
    sharedModifier: Modifier,
    onBackClick: () -> Unit,
) {
    val pagerState =
        rememberPagerState(
            initialPage = contentState.initialIndex,
            pageCount = { contentState.urls.size },
        )
    val activeZoomFraction = zoomFractions[pagerState.currentPage] ?: 0f

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = IMAGE_VIEWER_SINGLE_PAGE_THRESHOLD,
        userScrollEnabled =
            contentState.urls.size > IMAGE_VIEWER_SINGLE_PAGE_THRESHOLD &&
                activeZoomFraction <= IMAGE_VIEWER_SCROLL_LOCK_THRESHOLD,
    ) { page ->
        ImageViewerPage(
            page = page,
            imageUrl = contentState.urls[page],
            isInitiallyShared = page == contentState.initialIndex,
            sharedModifier = sharedModifier,
            zoomFractions = zoomFractions,
            onBackClick = onBackClick,
        )
    }

    if (contentState.urls.size > IMAGE_VIEWER_SINGLE_PAGE_THRESHOLD) {
        Text(
            text = "${pagerState.currentPage + IMAGE_VIEWER_PAGE_INDEX_OFFSET} / ${contentState.urls.size}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = IMAGE_VIEWER_PAGE_INDICATOR_ALPHA),
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = IMAGE_VIEWER_PAGE_INDICATOR_BOTTOM_PADDING),
        )
    }
}

@Composable
private fun ImageViewerPage(
    page: Int,
    imageUrl: String,
    isInitiallyShared: Boolean,
    sharedModifier: Modifier,
    zoomFractions: MutableMap<Int, Float>,
    onBackClick: () -> Unit,
) {
    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = IMAGE_VIEWER_MAX_ZOOM_FACTOR))
    val zoomableImageState = rememberZoomableImageState(zoomableState)
    val zoomFraction = zoomableState.zoomFraction ?: 0f
    val canPanImage = zoomFraction > IMAGE_VIEWER_SCROLL_LOCK_THRESHOLD

    LaunchedEffect(page, zoomFraction) {
        zoomFractions[page] = zoomFraction
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
                        sharedModifier
                    } else {
                        Modifier
                    },
                ),
        state = zoomableImageState,
        contentScale = ContentScale.Fit,
        onClick = { onBackClick() },
    )
}

@Composable
private fun BoxScope.ImageViewerCloseButton(onBackClick: () -> Unit) {
    IconButton(
        onClick = onBackClick,
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .padding(IMAGE_VIEWER_CLOSE_BUTTON_PADDING),
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_close),
            tint = Color.White,
        )
    }
}
