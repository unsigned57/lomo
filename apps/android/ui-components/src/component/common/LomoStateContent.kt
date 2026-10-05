package com.lomo.ui.component.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Animate presentation changes by a semantic key, without replaying motion for data refreshes. */
@Composable
fun <T> LomoStateContent(
    state: T,
    contentKey: (T) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val scheme = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        contentKey = contentKey,
        modifier = modifier,
        contentAlignment = Alignment.Center,
        transitionSpec = { fadeIn(scheme.defaultEffectsSpec()) togetherWith fadeOut(scheme.fastEffectsSpec()) },
        label = "ScreenPresentation",
        content = content,
    )
}
