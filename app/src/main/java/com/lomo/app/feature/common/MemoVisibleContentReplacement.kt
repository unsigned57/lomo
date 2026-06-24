package com.lomo.app.feature.common

import com.lomo.domain.model.Memo

data class MemoVisibleContentReplacement(
    val previousContent: String,
    val replacementContent: String,
) {
    fun applyTo(memo: Memo): Memo =
        if (memo.content == previousContent || memo.content == replacementContent) {
            memo.replacingVisibleContent(replacementContent)
        } else {
            memo
        }
}

fun Memo.replacingVisibleContent(newContent: String): Memo {
    val newRawContent =
        if (content.isNotEmpty() && rawContent.contains(content)) {
            rawContent.replaceFirst(content, newContent)
        } else {
            newContent
        }
    return copy(
        content = newContent,
        rawContent = newRawContent,
    )
}
