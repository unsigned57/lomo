package com.lomo.app.feature.memo

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EditableMemoSnapshot

/**
 * One open edit session: the verified full snapshot the editor binds to plus the durable draft
 * identity that owns this session's staged media. Constructing it requires an
 * [EditableMemoSnapshot], so a list preview — or a `copy(contentKind = Full)` forgery of one —
 * can never reach the editor as a baseline.
 */
data class MemoEditSession(
    val snapshot: EditableMemoSnapshot,
    val draftId: DraftId,
)
