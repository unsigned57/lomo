package com.lomo.app.testing

import com.lomo.app.feature.memo.MemoEditSession
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.EditableMemoSnapshot
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind

/**
 * Builds an edit session the way production does: a verified full snapshot carrying the body plus
 * its own CAS metadata, leased to [draftId]. Test memo fixtures that lack revision/fingerprint get
 * deterministic stand-ins so the completeness gate exercises the real path.
 */
internal fun Memo.verifiedEditSession(draftId: DraftId = DraftId("test-draft")): MemoEditSession =
    MemoEditSession(
        snapshot =
            EditableMemoSnapshot.fromFullSnapshot(
                copy(
                    contentKind = MemoContentKind.Full,
                    contentRevision = contentRevision ?: 1L,
                    fileFingerprint = fileFingerprint ?: "fp-$id",
                    projectedCharCount = rawContent.length.toLong(),
                ),
            ),
        draftId = draftId,
    )
