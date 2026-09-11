package com.lomo.app.feature.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import com.lomo.ui.theme.AppShapes

private val GALLERY_REEL_TOP_BAR_HORIZONTAL_PADDING = 16.dp
private val GALLERY_REEL_TOP_BAR_TOP_PADDING = 8.dp
private val GALLERY_REEL_TOP_BAR_TOUCH_SIZE = 44.dp
private val GALLERY_REEL_TOP_BAR_ICON_SIZE = 24.dp
private const val GALLERY_REEL_TOP_BAR_PILL_ALPHA = 0.72f
private val GALLERY_REEL_TOP_BAR_PILL_ELEVATION = 3.dp

@Composable
internal fun BoxScope.GalleryReelTopBar(
    onClose: () -> Unit,
    onShowMenu: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    horizontal = GALLERY_REEL_TOP_BAR_HORIZONTAL_PADDING,
                    vertical = GALLERY_REEL_TOP_BAR_TOP_PADDING,
                ),
    ) {
        GalleryReelTopBarPillButton(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.gallery_reel_close),
            onClick = onClose,
        )
        if (onShowMenu != null) {
            GalleryReelTopBarPillButton(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.cd_more_options),
                onClick = onShowMenu,
            )
        } else {
            Spacer(modifier = Modifier.size(GALLERY_REEL_TOP_BAR_TOUCH_SIZE))
        }
    }
}

@Composable
private fun GalleryReelTopBarPillButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = GALLERY_REEL_TOP_BAR_PILL_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = AppShapes.Full,
        tonalElevation = GALLERY_REEL_TOP_BAR_PILL_ELEVATION,
    ) {
        IconButton(
            onClick = onClick,
            colors =
                IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            modifier = Modifier.size(GALLERY_REEL_TOP_BAR_TOUCH_SIZE),
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(GALLERY_REEL_TOP_BAR_ICON_SIZE),
            )
        }
    }
}
