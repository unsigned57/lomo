package com.lomo.domain.usecase

import com.lomo.domain.model.DraftId
import com.lomo.domain.model.DraftMediaReconciliation
import com.lomo.domain.model.RecoverableDraftFailure
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.MediaRepository
import kotlinx.coroutines.flow.first

/**
 * Restart reconciliation for one recovered durable draft.
 *
 * Reads the draft's staged-media leases from the ledger authority and fails fast with a typed
 * [RecoverableDraftFailure] when any lease's bytes have vanished — those references can never be
 * promoted, so letting the draft continue would surface only as an opaque commit rejection.
 * Returns the full reconciliation so the caller can re-bind the draft's media references.
 *
 * The stage ledger lives inside the mounted projection adapter: reconciling while the engine has
 * no readable projection throws its mount precondition on the caller's thread. Recovery-path callers
 * legitimately race engine open at process start, so the capability suspends until
 * `mount.admitsProjectionReads` holds; non-readable terminal states keep the suspend parked until
 * the workspace is re-picked or the caller's scope is cancelled.
 */
open class ReconcileDraftMediaUseCase(
    private val mediaRepository: MediaRepository,
    private val engineReadinessRepository: EngineReadinessRepository,
) {
    open suspend operator fun invoke(draftId: DraftId): DraftMediaReconciliation {
        engineReadinessRepository.mount.first { it.admitsProjectionReads }
        val reconciliation = mediaRepository.reconcileDraftMedia(draftId)
        val missing = reconciliation.missing
        if (missing.isNotEmpty()) {
            throw RecoverableDraftFailure(missing.map { it.relativePath })
        }
        return reconciliation
    }
}
