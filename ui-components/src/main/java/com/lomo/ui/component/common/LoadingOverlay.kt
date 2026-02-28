package com.lomo.ui.component.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
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

@Composable
fun LoadingOverlay(
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val resolvedMessage = message ?: stringResource(R.string.loading_message)
    Surface(
        modifier = modifier.fillMaxSize().zIndex(100f),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = resolvedMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
