package com.lomo.app.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.theme.AppShapes

private const val GALLERY_REEL_IMAGE_INDICATOR_PAGE_INDEX_OFFSET = 1
private val GALLERY_REEL_INDICATOR_DOT_SIZE = 6.dp
private const val GALLERY_REEL_INDICATOR_PILL_ALPHA = 0.72f
private val GALLERY_REEL_INDICATOR_PILL_ELEVATION = 3.dp

data class GalleryReelImageIndicatorState(
    val currentPage: Int,
    val pageCount: Int,
)

@Composable
internal fun GalleryReelImageDots(
    state: GalleryReelImageIndicatorState,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = GALLERY_REEL_INDICATOR_PILL_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = AppShapes.Full,
        tonalElevation = GALLERY_REEL_INDICATOR_PILL_ELEVATION,
        modifier = modifier,
    ) {
        GalleryReelImageDotsRow(
            state = state,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun GalleryReelImageDotsInline(
    state: GalleryReelImageIndicatorState,
    modifier: Modifier = Modifier,
) {
    GalleryReelImageDotsRow(state = state, modifier = modifier)
}

@Composable
private fun GalleryReelImageDotsRow(
    state: GalleryReelImageIndicatorState,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Text(
            text =
                stringResource(
                    R.string.gallery_reel_image_indicator,
                    state.currentPage + GALLERY_REEL_IMAGE_INDICATOR_PAGE_INDEX_OFFSET,
                    state.pageCount,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
        repeat(state.pageCount) { index ->
            Box(
                modifier =
                    Modifier
                        .size(GALLERY_REEL_INDICATOR_DOT_SIZE)
                        .background(
                            color =
                                if (index == state.currentPage) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f)
                                },
                            shape = AppShapes.Full,
                        ),
            )
        }
    }
}
