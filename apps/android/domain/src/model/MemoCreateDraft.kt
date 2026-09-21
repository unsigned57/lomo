package com.lomo.domain.model

import kotlinx.serialization.Serializable

/**
 * Durable process-death recovery for an unsaved new-memo draft.
 *
 * This is the create variant of the same editor-draft protocol the edit draft uses: the payload is
 * a modeled record rather than a bare text blob, so recovery validates it before admitting it.
 */
@Serializable
data class MemoCreateDraft(
    val content: String,
) {
    init {
        require(content.length <= MemoConstraints.MAX_MEMO_LENGTH) {
            "Memo create draft content exceeds the memo length limit"
        }
    }
}
