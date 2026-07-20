package com.lomo.data.repository

import com.lomo.domain.model.isWritable
import com.lomo.domain.model.requireWritable
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.WriteFreezeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Process-local choke for workspace file mutations.
 *
 * Engine readiness is the durable write authority; freeze is the temporary switch barrier. Every
 * shared writer (markdown/media storage, workspace media access, outbox drain, remote-sync local
 * apply) must consult this collaborator so process-start drains and remote/migration apply cannot
 * mutate files outside Ready + !freeze.
 */
class WorkspaceWriteAuthority(
    private val engineReadinessRepository: EngineReadinessRepository,
    private val writeFreezeRepository: WriteFreezeRepository,
) {
    fun requireWritable() {
        engineReadinessRepository.readiness.value.requireWritable(
            writeFrozen = writeFreezeRepository.isFrozen.value,
        )
    }

    fun isWritable(): Boolean =
        engineReadinessRepository.readiness.value.isWritable(
            writeFrozen = writeFreezeRepository.isFrozen.value,
        )

    /** Emits true only while Ready and unfrozen; used by outbox drain to re-request after bootstrap. */
    fun isWritableFlow(): Flow<Boolean> =
        combine(
            engineReadinessRepository.readiness,
            writeFreezeRepository.isFrozen,
        ) { readiness, frozen ->
            readiness.isWritable(writeFrozen = frozen)
        }.distinctUntilChanged()
}
