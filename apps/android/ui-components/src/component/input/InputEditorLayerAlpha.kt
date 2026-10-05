package com.lomo.ui.component.input

import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

@Composable
internal fun rememberInputEditorLayerAlpha(
    visible: (InputSheetPresentationState) -> Boolean,
    label: String,
): State<Float> {
    val scheme = MaterialTheme.motionScheme
    return LocalInputSheetPresentationTransition.current.animateFloat(
        transitionSpec = { scheme.defaultEffectsSpec() },
        label = label,
    ) { if (visible(it)) 1f else 0f }
}
