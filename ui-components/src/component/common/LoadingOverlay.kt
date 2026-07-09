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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.zIndex
import com.lomo.ui.generated.resources.Res
import com.lomo.ui.generated.resources.*

@Composable
fun LoadingOverlay(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val resolvedMessage = message ?: stringResource(Res.string.loading_message)
    Surface(
        modifier = modifier.fillMaxSize().zIndex(LoadingOverlayTokens.ZIndex),
        color = LoadingOverlayTokens.backgroundColor(MaterialTheme.colorScheme),
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
                Spacer(modifier = Modifier.height(LoadingOverlayTokens.MessageSpacing))
                Text(
                    text = resolvedMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
