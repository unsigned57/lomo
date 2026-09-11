package com.lomo.domain.model

/**
 * One Markdown task-list item projected by the application session.
 *
 * [lineIndex] is the zero-based source line in that memo body. Session toggle addresses that
 * line; it is not a byte span and must not be mixed with editor checkbox spans.
 */
data class MemoTask(
    val memoId: String,
    val lineIndex: Int,
    val done: Boolean,
    val text: String,
    val sourcePath: String,
) {
    init {
        require(memoId.isNotBlank()) { "task memoId must be non-blank" }
        require(lineIndex >= 0) { "task lineIndex must be non-negative" }
        require(sourcePath.isNotBlank()) { "task sourcePath must be non-blank" }
    }
}
