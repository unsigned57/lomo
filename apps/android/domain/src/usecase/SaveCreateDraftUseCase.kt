package com.lomo.domain.usecase

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MemoCreateDraft
import com.lomo.domain.repository.MemoCreateDraftRepository

open class SaveCreateDraftUseCase(
    private val repository: MemoCreateDraftRepository,
) {
    /**
     * Persists the create draft under its durable [draftId]. The same id owns this draft's
     * staged-media leases, so it must be the identity the editor session already minted — never a
     * fresh one per save, which would orphan the earlier leases. A null/empty content clears the
     * draft; lease release happens in the discard path that owns media cleanup.
     */
    open suspend operator fun invoke(
        draftId: DraftId,
        content: String?,
    ) {
        if (content.isNullOrEmpty()) {
            repository.clear()
        } else {
            repository.write(MemoCreateDraft(draftId = draftId, content = content))
        }
    }
}
