package com.lomo.app.testing.fakes

import com.lomo.domain.model.markdown.MarkdownSourceSpan
import com.lomo.domain.repository.MarkdownWorkspaceRepository
import com.lomo.domain.usecase.ToggleMemoCheckboxUseCase

/**
 * Builds a [ToggleMemoCheckboxUseCase] whose workspace toggle updates [FakeMemoStore] content so
 * host ViewModel tests observe post-cutover action-span toggles without a real engine file write.
 */
fun storeBackedToggleMemoCheckboxUseCase(store: FakeMemoStore): ToggleMemoCheckboxUseCase {
    val mutation = FakeMemoMutationRepository(store)
    val workspace =
        object : MarkdownWorkspaceRepository by FakeMarkdownWorkspaceRepository() {
            override suspend fun toggleTask(
                memoIdentity: String,
                actionSpan: MarkdownSourceSpan,
            ): com.lomo.domain.model.MemoDocumentMutation {
                val memo =
                    store.currentActiveMemos().firstOrNull { it.id == memoIdentity }
                        ?: error("memo not in fake store: $memoIdentity")
                val updated =
                    when {
                        memo.content.contains("[ ]") -> memo.content.replaceFirst("[ ]", "[x]")
                        memo.content.contains("[x]", ignoreCase = true) ->
                            memo.content.replaceFirst(Regex("""\[[xX]\]"""), "[ ]")
                        else -> memo.content
                    }
                return com.lomo.domain.model.MemoDocumentMutation(
                    operationId = "fake-toggle-$memoIdentity",
                    expectedRevision = memo.contentRevision ?: 0L,
                    expectedFingerprint = memo.fileFingerprint ?: "fake-fingerprint",
                    facts =
                        com.lomo.domain.model.MemoDocumentFacts(
                            memoId = memo.id,
                            sourcePath = "memos/${memo.id}.md",
                            fileFingerprint = "fake-fingerprint-$updated",
                            chronologyEpochMs = memo.timestamp,
                            content = updated,
                            tags = memo.tags,
                            attachmentPaths = memo.imageUrls,
                            reminders = memo.reminders,
                            hasTodo = updated.contains("[ ]") || updated.contains("[x]", ignoreCase = true),
                            hasUrl = memo.content.contains("http://") || memo.content.contains("https://"),
                        ),
                )
            }
        }
    return ToggleMemoCheckboxUseCase(
        workspaceRepository = workspace,
        memoMutationRepository = mutation,
    )
}
