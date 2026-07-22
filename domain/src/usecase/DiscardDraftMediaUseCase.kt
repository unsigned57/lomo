package com.lomo.domain.usecase

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
    open suspend operator fun invoke(filenames: Collection<String>) {
        filenames.forEach { filename ->
            try {
                mediaRepository.removeImage(MediaEntryId(filename))
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                // Best-effort cleanup.
            }
        }
    }
}
