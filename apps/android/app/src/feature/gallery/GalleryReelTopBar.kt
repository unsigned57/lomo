package com.lomo.app.feature.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.component.common.LomoIconButton
import com.lomo.ui.icon.LomoIcon
import com.lomo.ui.icon.LomoIconSpec
import com.lomo.ui.icon.LomoIcons
import com.lomo.ui.theme.AppShapes
import com.lomo.ui.util.LocalAppHapticFeedback
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

private val GALLERY_REEL_TOP_BAR_HORIZONTAL_PADDING = 16.dp
private val GALLERY_REEL_TOP_BAR_TOP_PADDING = 8.dp
private val GALLERY_REEL_TOP_BAR_TOUCH_SIZE = 44.dp
private val GALLERY_REEL_TOP_BAR_ICON_SIZE = 24.dp
private const val GALLERY_REEL_TOP_BAR_GLASS_TINT_ALPHA = 0.24f

@Composable
internal fun GalleryReelTopBar(
    hazeState: HazeState,
    onClose: () -> Unit,
    onShowMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalAppHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                horizontal = GALLERY_REEL_TOP_BAR_HORIZONTAL_PADDING,
                vertical = GALLERY_REEL_TOP_BAR_TOP_PADDING,
            ),
    ) {
        GalleryReelTopBarPillButton(
            hazeState = hazeState,
            icon = LomoIcons.Close,
            contentDescription = stringResource(R.string.gallery_reel_close),
            onClick = {
                haptic.medium()
                onClose()
            },
        )
        if (onShowMenu != null) {
            GalleryReelTopBarPillButton(
                hazeState = hazeState,
                icon = LomoIcons.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                onClick = {
                    haptic.medium()
                    onShowMenu()
                },
            )
        } else {
            Spacer(modifier = Modifier.size(GALLERY_REEL_TOP_BAR_TOUCH_SIZE))
        }
    }
}

@Composable
private fun GalleryReelTopBarPillButton(
    hazeState: HazeState,
    icon: LomoIconSpec,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val glassStyle = remember(surfaceColor) {
        GlassStyle.clear.then {
            shape(AppShapes.Full)
            backgroundColor(surfaceColor)
            tint(surfaceColor.copy(alpha = GALLERY_REEL_TOP_BAR_GLASS_TINT_ALPHA))
        }
    }
    Surface(
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = AppShapes.Full,
        modifier = Modifier.hazeGlass(input = HazeInput.Sources(hazeState), style = glassStyle),
    ) {
        LomoIconButton(
            onClick = onClick,
            colors =
            IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.size(GALLERY_REEL_TOP_BAR_TOUCH_SIZE),
        ) {
            LomoIcon(
                icon = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(GALLERY_REEL_TOP_BAR_ICON_SIZE),
            )
        }
    }
}
