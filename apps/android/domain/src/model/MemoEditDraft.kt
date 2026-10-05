package com.lomo.domain.model

import kotlinx.serialization.Serializable

/**
 * A recoverable editor session bound to one immutable memo baseline.
 *
 * The draft is never a replacement for the store projection: it is admissible only when all three
 * identity facts still match the memo that was opened. This keeps process-death recovery from
 * turning an old buffer into an implicit last-writer-wins update.
 */
@Serializable
data class MemoEditDraft(
    val draftId: DraftId,
    val memoId: String,
    val baselineRevision: Long,
    val baselineFingerprint: String,
    val content: String,
) {
    init {
        require(memoId.isNotBlank() && memoId.length <= MAX_MEMO_ID_LENGTH) {
            "Memo edit draft id is invalid"
        }
        require(baselineRevision > 0L) { "Memo edit draft baseline revision must be positive" }
        require(
            baselineFingerprint.isNotBlank() &&
                baselineFingerprint.length <= MAX_FINGERPRINT_LENGTH,
        ) { "Memo edit draft baseline fingerprint is invalid" }
        require(content.length <= MemoConstraints.MAX_MEMO_LENGTH) {
            "Memo edit draft content exceeds the memo length limit"
        }
    }

    /** Returns whether this draft can be reopened against the supplied current snapshot. */
    fun matchesBaseline(
        currentMemoId: String,
        currentRevision: Long,
        currentFingerprint: String,
    ): Boolean =
        memoId == currentMemoId &&
            baselineRevision == currentRevision &&
            baselineFingerprint == currentFingerprint

    private companion object {
        const val MAX_MEMO_ID_LENGTH = 512
        const val MAX_FINGERPRINT_LENGTH = 256
    }
}
