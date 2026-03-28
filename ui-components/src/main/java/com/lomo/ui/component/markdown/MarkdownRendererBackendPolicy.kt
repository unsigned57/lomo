package com.lomo.ui.component.markdown

internal fun shouldUseModernMarkdownBackend(
    maxVisibleBlocks: Int,
    hasTodoToggleHandler: Boolean,
    hasTodoOverrides: Boolean,
    hasKnownTagsToStrip: Boolean,
    hasImageClickHandler: Boolean,
    hasPrecomputedNode: Boolean,
): Boolean {
    val memoSpecificFeaturesRequested =
        maxVisibleBlocks != Int.MAX_VALUE ||
            hasTodoToggleHandler ||
            hasTodoOverrides ||
            hasKnownTagsToStrip ||
            hasImageClickHandler ||
            hasPrecomputedNode
    return memoSpecificFeaturesRequested || !memoSpecificFeaturesRequested
}
