package com.lomo.ui.component.menu

internal fun resolveMemoActionRowLayoutMode(
    equalWidthActions: Boolean,
    useHorizontalScroll: Boolean,
): MemoActionRowLayoutMode =
    if (equalWidthActions && !useHorizontalScroll) {
        MemoActionRowLayoutMode.EQUAL_WIDTH_STATIC
    } else {
        MemoActionRowLayoutMode.LAZY_ROW
    }
