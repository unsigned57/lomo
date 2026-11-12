package com.lomo.ui.component.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.cd_page_indicator
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.LocalLomoMotion
import org.jetbrains.compose.resources.stringResource

/** M3 Expressive page-indicator variants: dots up to five pages, compact beyond that. */
enum class LomoPageIndicatorVariant {
    Hidden,
    Dots,
    Compact,
}

@Immutable
data class LomoPageIndicatorState(val variant: LomoPageIndicatorVariant, val activeIndex: Int, val pageCount: Int)

/** M3E switches from dots to the compact indicator when a pager exceeds five pages. */
const val LOMO_PAGE_INDICATOR_MAX_DOT_PAGES = 5

/**
 * Resolves the M3E page-indicator variant for a pager position. The active index is clamped so a
 * pager mid-reload (item count shrinking under a stale index) can never highlight a missing page.
 */
fun resolveLomoPageIndicatorState(current: Int, total: Int): LomoPageIndicatorState {
    if (total <= 1) {
        return LomoPageIndicatorState(
            variant = LomoPageIndicatorVariant.Hidden,
            activeIndex = 0,
            pageCount = total.coerceAtLeast(0),
        )
    }
    return LomoPageIndicatorState(
        variant =
        if (total <= LOMO_PAGE_INDICATOR_MAX_DOT_PAGES) {
            LomoPageIndicatorVariant.Dots
        } else {
            LomoPageIndicatorVariant.Compact
        },
        activeIndex = current.coerceIn(0, total - 1),
        pageCount = total,
    )
}

/**
 * M3 Expressive page indicator. Up to [LOMO_PAGE_INDICATOR_MAX_DOT_PAGES] pages render as dots
 * where the active dot stretches into a pill; beyond that the compact "current / total" pill is
 * used. [onMedia] switches to the white-on-scrim palette for image and reel overlays.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LomoPageIndicator(current: Int, total: Int, modifier: Modifier = Modifier, onMedia: Boolean = false) {
    val state = remember(current, total) { resolveLomoPageIndicatorState(current = current, total = total) }
    if (state.variant == LomoPageIndicatorVariant.Hidden) {
        return
    }
    val description =
        stringResource(Res.string.cd_page_indicator, state.activeIndex + 1, state.pageCount)
    when (state.variant) {
        LomoPageIndicatorVariant.Dots ->
            LomoPageIndicatorDots(state = state, onMedia = onMedia, description = description, modifier = modifier)

        LomoPageIndicatorVariant.Compact ->
            LomoPageIndicatorCompact(state = state, onMedia = onMedia, description = description, modifier = modifier)

        LomoPageIndicatorVariant.Hidden -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LomoPageIndicatorDots(
    state: LomoPageIndicatorState,
    onMedia: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    val motionScheme = LocalLomoMotion.current.component
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(LomoPageIndicatorTokens.DotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(state.pageCount) { index ->
            val isActive = index == state.activeIndex
            val dotWidth by animateDpAsState(
                targetValue = if (isActive) LomoPageIndicatorTokens.ActiveDotWidth else LomoPageIndicatorTokens.DotSize,
                animationSpec = motionScheme.fastSpatialSpec(),
                label = "PageIndicatorDotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (isActive) activeDotColor(onMedia) else inactiveDotColor(onMedia),
                animationSpec = motionScheme.defaultEffectsSpec(),
                label = "PageIndicatorDotColor",
            )
            Box(
                modifier =
                Modifier
                    .size(width = dotWidth, height = LomoPageIndicatorTokens.DotSize)
                    .background(color = dotColor, shape = AppShapes.Full),
            )
        }
    }
}

@Composable
private fun LomoPageIndicatorCompact(
    state: LomoPageIndicatorState,
    onMedia: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        shape = AppShapes.Full,
        color =
        if (onMedia) {
            Color.Black.copy(alpha = LomoPageIndicatorTokens.MediaScrimAlpha)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor =
        if (onMedia) {
            Color.White
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = "${state.activeIndex + 1} / ${state.pageCount}",
            style = MaterialTheme.typography.labelMedium,
            modifier =
            Modifier.padding(
                horizontal = LomoPageIndicatorTokens.CompactHorizontalPadding,
                vertical = LomoPageIndicatorTokens.CompactVerticalPadding,
            ),
        )
    }
}

@Composable
private fun activeDotColor(onMedia: Boolean): Color = if (onMedia) {
    Color.White
} else {
    MaterialTheme.colorScheme.primary
}

@Composable
private fun inactiveDotColor(onMedia: Boolean): Color = if (onMedia) {
    Color.White.copy(alpha = LomoPageIndicatorTokens.InactiveDotAlpha)
} else {
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LomoPageIndicatorTokens.InactiveDotAlpha)
}

private object LomoPageIndicatorTokens {
    val DotSize = 8.dp
    val ActiveDotWidth = 20.dp
    val DotSpacing = 6.dp
    val CompactHorizontalPadding = 12.dp
    val CompactVerticalPadding = 4.dp
    const val InactiveDotAlpha = 0.38f
    const val MediaScrimAlpha = 0.42f
}
