package com.lomo.domain.usecase

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

/**
 * Best-effort draft media cleanup. Stage discard is preferred; this removes the committed media
 * entries (owner-resolved relative paths) tracked by the editor when the draft is abandoned.
 */
open class DiscardDraftMediaUseCase(
    private val mediaRepository: MediaRepository,
) {
    open suspend operator fun invoke(
        entryIds: Collection<String>,
        draftId: DraftId,
    ) {
        entryIds.forEach { entryId ->
            try {
                // behavior-contract: loop-io-ok: no bulk removeImage API; each iteration is one bounded media id
                mediaRepository.removeImage(MediaEntryId(entryId), draftId)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                // behavior-contract: silent-result-ok: draft discard is best-effort; a missing entry is ignored
            }
        }
        // Release the draft's lease claims last: every staged byte still owned by this draft dies
        // exactly once, and bytes re-leased to a pending operation survive the discard.
        mediaRepository.releaseDraftLeases(draftId)
    }
}
