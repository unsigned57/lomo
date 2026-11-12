package com.lomo.ui.component.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    // Page roots may impose a full-screen minimum. Keep those constraints on the host,
    // rather than allowing the indicator's path to expand to the size of the page.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LoadingIndicator(
            modifier = Modifier.size(LoadingIndicatorDefaults.ContainerWidth, LoadingIndicatorDefaults.ContainerHeight),
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    indicatorColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    shape: Shape = MaterialTheme.shapes.extraLarge,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val indicatorModifier =
            Modifier.size(LoadingIndicatorDefaults.ContainerWidth, LoadingIndicatorDefaults.ContainerHeight)
        if (progress == null) {
            ContainedLoadingIndicator(
                modifier = indicatorModifier,
                indicatorColor = indicatorColor,
                containerColor = containerColor,
                containerShape = shape,
            )
        } else {
            ContainedLoadingIndicator(
                progress,
                modifier = indicatorModifier,
                indicatorColor = indicatorColor,
                containerColor = containerColor,
                containerShape = shape,
            )
        }
    }
}
