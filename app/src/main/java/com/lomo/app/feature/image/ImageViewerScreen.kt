package com.lomo.app.feature.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@Composable
fun ImageViewerScreen(
    imageUrls: List<String>,
    initialIndex: Int,
    onBackClick: () -> Unit,
) {
    val normalizedUrls =
        remember(imageUrls) {
            imageUrls
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        }
    val effectiveUrls = normalizedUrls.ifEmpty { emptyList() }
    val effectiveInitialIndex =
        if (effectiveUrls.isEmpty()) {
            0
        } else {
            initialIndex.coerceIn(0, effectiveUrls.lastIndex)
        }
    val zoomFractions = remember(effectiveUrls) { mutableStateMapOf<Int, Float>() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        val sharedTransitionScope = com.lomo.ui.util.LocalSharedTransitionScope.current
        val animatedVisibilityScope = com.lomo.ui.util.LocalAnimatedVisibilityScope.current

        @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
        val sharedModifier =
            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        rememberSharedContentState(key = effectiveUrls.getOrNull(effectiveInitialIndex).orEmpty()),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            } else {
                Modifier
            }

        if (effectiveUrls.isNotEmpty()) {
            val pagerState =
                rememberPagerState(
                    initialPage = effectiveInitialIndex,
                    pageCount = { effectiveUrls.size },
                )
            val activeZoomFraction = zoomFractions[pagerState.currentPage] ?: 0f
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = effectiveUrls.size > 1 && activeZoomFraction <= 0.01f,
            ) { page ->
                val imageUrl = effectiveUrls[page]
                val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 5f))
                val zoomableImageState = rememberZoomableImageState(zoomableState)
                val zoomFraction = zoomableState.zoomFraction ?: 0f
                val canPanImage = zoomFraction > 0.01f

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
                                if (page == effectiveInitialIndex) {
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

            if (effectiveUrls.size > 1) {
                androidx.compose.material3.Text(
                    text = "${pagerState.currentPage + 1} / ${effectiveUrls.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp),
                )
            }
        }

        IconButton(
            onClick = onBackClick,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint = Color.White,
            )
        }
    }
}
