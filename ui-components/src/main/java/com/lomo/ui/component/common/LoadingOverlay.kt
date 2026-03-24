package com.lomo.ui.component.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lomo.ui.R

private const val LOADING_OVERLAY_Z_INDEX = 100f
private const val LOADING_OVERLAY_BACKGROUND_ALPHA = 0.9f
private val LOADING_OVERLAY_MESSAGE_SPACING = 16.dp

@Composable
fun LoadingOverlay(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val resolvedMessage = message ?: stringResource(R.string.loading_message)
    Surface(
        modifier = modifier.fillMaxSize().zIndex(LOADING_OVERLAY_Z_INDEX),
        color = MaterialTheme.colorScheme.background.copy(alpha = LOADING_OVERLAY_BACKGROUND_ALPHA),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ExpressiveContainedLoadingIndicator(
                    indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
                Spacer(modifier = Modifier.height(LOADING_OVERLAY_MESSAGE_SPACING))
                Text(
                    text = resolvedMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
