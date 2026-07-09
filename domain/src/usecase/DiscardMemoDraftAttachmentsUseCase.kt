package com.lomo.domain.usecase

import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

open class DiscardMemoDraftAttachmentsUseCase
(
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
