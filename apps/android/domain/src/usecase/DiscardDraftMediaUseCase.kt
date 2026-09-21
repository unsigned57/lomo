package com.lomo.domain.usecase

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

/**
 * Best-effort draft media cleanup. Stage discard is preferred; this removes committed basenames
 * tracked by the editor when the draft is abandoned.
 */
open class DiscardDraftMediaUseCase(
    private val mediaRepository: MediaRepository,
) {
    open suspend operator fun invoke(
        filenames: Collection<String>,
        draftId: DraftId,
    ) {
        filenames.forEach { filename ->
            try {
                // behavior-contract: loop-io-ok: no bulk removeImage API; each iteration is one bounded media id
                mediaRepository.removeImage(MediaEntryId(filename), draftId)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                // behavior-contract: silent-result-ok: draft discard is best-effort; missing basename is ignored
            }
        }
    }
}
