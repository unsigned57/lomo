package com.lomo.domain.model

import kotlinx.serialization.Serializable

/**
 * Durable process-death recovery for an unsaved new-memo draft.
 *
 * This is the create variant of the same editor-draft protocol the edit draft uses: the payload is
 * a modeled record rather than a bare text blob, so recovery validates it before admitting it.
 *
 * [draftId] is the staged-media lease identity recorded at first persist; it must survive process
 * death so a recovered draft still owns the media it staged before the kill.
 */
@Serializable
data class MemoCreateDraft(
    val draftId: DraftId,
    val content: String,
) {
    init {
        require(content.length <= MemoConstraints.MAX_MEMO_LENGTH) {
            "Memo create draft content exceeds the memo length limit"
        }
    }
}
