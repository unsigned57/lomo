package com.lomo.ui.component.dialog

import com.lomo.domain.model.SimpleLineDiff

internal sealed interface ConflictTextDiffPresentation {
    data class Diff(
        val hunks: List<SimpleLineDiff.DiffHunk>,
    ) : ConflictTextDiffPresentation

    data object NoTextDiffs : ConflictTextDiffPresentation

    data object TooLarge : ConflictTextDiffPresentation
}

internal fun resolveConflictTextDiffPresentation(
    result: SimpleLineDiff.DiffResult,
): ConflictTextDiffPresentation =
    when (result) {
        is SimpleLineDiff.DiffResult.Computed ->
            if (result.hunks.isEmpty()) {
                ConflictTextDiffPresentation.NoTextDiffs
            } else {
                ConflictTextDiffPresentation.Diff(result.hunks)
            }
        is SimpleLineDiff.DiffResult.TooLarge -> ConflictTextDiffPresentation.TooLarge
    }
