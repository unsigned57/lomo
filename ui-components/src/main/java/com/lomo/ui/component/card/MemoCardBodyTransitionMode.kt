package com.lomo.ui.component.card

internal enum class MemoCardBodyTransitionMode {
    Snap,
    VerticalVisibility,
}

internal fun resolveMemoCardBodyTransitionMode(shouldShowExpand: Boolean): MemoCardBodyTransitionMode =
    if (shouldShowExpand) {
        MemoCardBodyTransitionMode.VerticalVisibility
    } else {
        MemoCardBodyTransitionMode.Snap
    }
