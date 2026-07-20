package com.lomo.domain.usecase

import com.lomo.domain.model.Memo
import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.repository.MemoMutationRepository

/** Routes a Rust-issued task action span back to the same workspace semantic owner. */
class ToggleMemoCheckboxUseCase(
    private val workspaceRepository: MarkdownWorkspaceRepository,
    private val memoMutationRepository: MemoMutationRepository,
) {
    suspend operator fun invoke(
        memo: Memo,
        actionSpan: MarkdownSourceSpan,
    ): String {
        val updatedContent =
            workspaceRepository.toggleTask(
                memoIdentity = memo.id,
                actionSpan = actionSpan,
            )
        memoMutationRepository.refreshMemos()
        return updatedContent
    }
}
