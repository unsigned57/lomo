package com.lomo.app.feature.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R

private val GALLERY_REEL_TOP_BAR_PADDING = 16.dp

@Composable
internal fun BoxScope.GalleryReelTopBar(
    onClose: () -> Unit,
    onShowMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(GALLERY_REEL_TOP_BAR_PADDING),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.gallery_reel_close),
                tint = Color.White,
            )
        }
        IconButton(onClick = onShowMenu) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(com.lomo.ui.R.string.cd_more_options),
                tint = Color.White,
            )
        }
    }
}

