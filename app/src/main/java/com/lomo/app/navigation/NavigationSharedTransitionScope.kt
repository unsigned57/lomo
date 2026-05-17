package com.lomo.app.navigation

import androidx.compose.runtime.Composable

@Composable
internal fun ProvideSharedAnimationLocals(
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.lomo.ui.util.LocalSharedTransitionScope provides sharedTransitionScope,
        com.lomo.ui.util.LocalAnimatedVisibilityScope provides animatedVisibilityScope,
    ) {
        content()
    }
}
