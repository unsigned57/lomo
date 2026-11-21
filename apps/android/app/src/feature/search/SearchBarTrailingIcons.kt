package com.lomo.app.feature.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.domain.model.MemoSearchMode
import com.lomo.ui.component.common.LomoFilledTonalButton
import com.lomo.ui.component.common.lomoCompactInteractiveHeight
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.theme.LocalLomoMotion
import com.lomo.ui.util.LocalAppHapticFeedback

/**
 * Determines whether the floating search mode capsule should be visible.
 *
 * Contract:
 * - Hidden when on the search page without software keyboard active.
 * - Visible when the IME is active and no query text has been typed.
 * - Hidden after typing begins (query text non-empty), when IME hides, or when bars hide.
 */
internal fun isSearchModeCapsuleVisible(
    isBarsVisible: Boolean,
    isImeVisible: Boolean,
    hasQueryText: Boolean,
): Boolean = isBarsVisible && isImeVisible && !hasQueryText

/**
 * Material 3 Expressive floating search mode capsule.
 * Positioned above the software keyboard / bottom navigation bar for effortless thumb reach.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SearchModeFloatingCapsule(
    searchMode: MemoSearchMode,
    onToggleSearchMode: () -> Unit,
    modifier: Modifier = Modifier,
    appearanceFraction: Float = 1f,
) {
    val isFuzzy = searchMode == MemoSearchMode.Fuzzy
    val haptic = LocalAppHapticFeedback.current
    val modeDescription =
        stringResource(
            if (isFuzzy) {
                R.string.search_mode_switch_to_fulltext
            } else {
                R.string.search_mode_switch_to_fuzzy
            },
        )

    Surface(
        shape = AppShapes.Full,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 0.dp,
        border =
        BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        modifier =
        modifier
            .graphicsLayer {
                val opacity = appearanceFraction.coerceIn(0f, 1f)
                shadowElevation = 8.dp.toPx() * opacity
                shape = AppShapes.Full
                alpha = opacity
                compositingStrategy = CompositingStrategy.ModulateAlpha
                scaleX = CAPSULE_ENTER_SCALE + (1f - CAPSULE_ENTER_SCALE) * appearanceFraction
                scaleY = scaleX
            }
            .semantics { contentDescription = modeDescription },
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchModeSegmentOption(
                selected = !isFuzzy,
                icon = LomoIcons.Search,
                label = stringResource(R.string.search_mode_fulltext),
                onClick = {
                    if (isFuzzy) {
                        haptic.medium()
                        onToggleSearchMode()
                    }
                },
            )
            SearchModeSegmentOption(
                selected = isFuzzy,
                icon = LomoIcons.ManageSearch,
                label = stringResource(R.string.search_mode_fuzzy),
                onClick = {
                    if (!isFuzzy) {
                        haptic.medium()
                        onToggleSearchMode()
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchModeSegmentOption(selected: Boolean, icon: LomoIconSpec, label: String, onClick: () -> Unit) {
    val componentMotion = LocalLomoMotion.current.component
    val containerColor by animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SearchModeSegmentContainer",
    )
    val contentColor by animateColorAsState(
        targetValue =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = componentMotion.fastEffectsSpec(),
        label = "SearchModeSegmentContent",
    )

    LomoFilledTonalButton(
        onClick = onClick,
        shapes = ButtonShapes(AppShapes.Full, AppShapes.Full),
        colors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.lomoCompactInteractiveHeight(34.dp),
    ) {
        LomoIcon(
            icon = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private const val CAPSULE_ENTER_SCALE = 0.8f
