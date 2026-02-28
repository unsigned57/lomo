package com.lomo.app.feature.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lomo.app.R
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage

@Composable
fun ImageViewerScreen(
    url: String,
    onBackClick: () -> Unit,
) {
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
                        rememberSharedContentState(key = url),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            } else {
                Modifier
            }

        ZoomableAsyncImage(
            model = url,
            contentDescription = stringResource(R.string.cd_image_viewer_fullscreen),
            modifier = Modifier.fillMaxSize().then(sharedModifier),
            onClick = { onBackClick() }, // Tap to dismiss
        )

        // Close button
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
