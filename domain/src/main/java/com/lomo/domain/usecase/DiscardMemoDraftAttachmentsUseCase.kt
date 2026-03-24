package com.lomo.domain.usecase

import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException

class DiscardMemoDraftAttachmentsUseCase
(
        private val mediaRepository: MediaRepository,
    ) {
        suspend operator fun invoke(filenames: Collection<String>) {
            filenames.forEach { filename ->
                try {
                    mediaRepository.removeImage(MediaEntryId(filename))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // Best-effort cleanup.
                }
            }
        }
    }
