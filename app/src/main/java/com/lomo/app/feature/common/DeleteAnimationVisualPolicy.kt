package com.lomo.app.feature.common

internal data class DeleteAnimationVisualPolicy(
    val animatePlacement: Boolean,
    val keepStableAlphaLayer: Boolean,
)

internal fun resolveDeleteAnimationVisualPolicy(isDeleting: Boolean): DeleteAnimationVisualPolicy =
    if (isDeleting) {
        DeleteAnimationVisualPolicy(
            animatePlacement = false,
            keepStableAlphaLayer = true,
        )
    } else {
        DeleteAnimationVisualPolicy(
            animatePlacement = true,
            keepStableAlphaLayer = false,
        )
    }
