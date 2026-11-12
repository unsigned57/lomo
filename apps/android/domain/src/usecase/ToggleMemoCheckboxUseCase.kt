package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository

/** Routes a Rust-issued task action span back to the same workspace semantic owner. */
class ToggleMemoCheckboxUseCase(private val workspaceRepository: MarkdownWorkspaceRepository) {
    suspend operator fun invoke(memo: Memo, actionSpan: MarkdownSourceSpan): String {
        val committedContent =
            workspaceRepository.toggleTask(
                operationId = com.lomo.domain.model.MemoOperationId.mint(),
                memoIdentity = memo.id,
                actionSpan = actionSpan,
            )
        return committedContent
    }
}
