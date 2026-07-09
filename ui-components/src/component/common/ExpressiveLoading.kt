package com.lomo.ui.component.common

import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(
        modifier = modifier,
        color = color,
    )
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
    if (progress == null) {
        ContainedLoadingIndicator(
            modifier = modifier,
            indicatorColor = indicatorColor,
            containerColor = containerColor,
            containerShape = shape,
        )
    } else {
        ContainedLoadingIndicator(
            progress,
            modifier = modifier,
            indicatorColor = indicatorColor,
            containerColor = containerColor,
            containerShape = shape,
        )
    }
}
