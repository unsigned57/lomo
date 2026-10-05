package com.lomo.domain.usecase

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.DraftMediaReconciliation
import com.lomo.domain.model.RecoverableDraftFailure
import com.lomo.domain.repository.MediaRepository

/**
 * Restart reconciliation for one recovered durable draft.
 *
 * Reads the draft's staged-media leases from the ledger authority and fails fast with a typed
 * [RecoverableDraftFailure] when any lease's bytes have vanished — those references can never be
 * promoted, so letting the draft continue would surface only as an opaque commit rejection.
 * Returns the full reconciliation so the caller can re-bind the draft's media references.
 */
open class ReconcileDraftMediaUseCase(
    private val mediaRepository: MediaRepository,
) {
    open suspend operator fun invoke(draftId: DraftId): DraftMediaReconciliation {
        val reconciliation = mediaRepository.reconcileDraftMedia(draftId)
        val missing = reconciliation.missing
        if (missing.isNotEmpty()) {
            throw RecoverableDraftFailure(missing.map { it.relativePath })
        }
        return reconciliation
    }
}
