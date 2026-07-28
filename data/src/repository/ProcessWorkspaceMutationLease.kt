package com.lomo.data.repository

import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.isWritable
import com.lomo.domain.model.requireWritable
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Process-local admission registry for workspace mutations.
 *
 * Admission is a single synchronized step: a writer cannot be admitted between the moment a
 * transition closes admissions and the moment the drain observes it. Release is deliberately
 * non-suspending so it can run in a `finally` without a cancellation window that would leave the
 * drain waiting forever on a writer that is already gone.
 *
 * Engine readiness stays the durable write authority; this lease is the barrier that makes a switch
 * observable to writers instead of racing them.
 */
class ProcessWorkspaceMutationLease(
    private val engineReadinessRepository: EngineReadinessRepository,
) : WorkspaceMutationLease {
    private val monitor = Any()
    private val admissionsOpen = MutableStateFlow(true)
    private val drained = MutableStateFlow(true)
    private var inFlight = 0

    override val authority: Flow<WorkspaceAuthority?> = engineReadinessRepository.workspaceAuthority

    /** Exposed for drains that need to observe when a transition opens or closes admissions. */
    val transitionOpen: StateFlow<Boolean> = admissionsOpen.asStateFlow()

    override fun isWritable(): Boolean =
        synchronized(monitor) {
            engineReadinessRepository.readiness.value.isWritable(writeFrozen = !admissionsOpen.value)
        }

    override fun isWritableFlow(): Flow<Boolean> =
        combine(
            engineReadinessRepository.readiness,
            admissionsOpen,
        ) { readiness, open ->
            readiness.isWritable(writeFrozen = !open)
        }.distinctUntilChanged()

    override suspend fun <T> withWrite(block: suspend (WorkspaceAuthority) -> T): T {
        currentAdmission()?.let { held -> return block(held) }
        val admitted = admit()
        try {
            return withContext(AdmittedWrite(admitted)) { block(admitted) }
        } finally {
            release()
        }
    }

    override suspend fun <T : Any> withWriteOrNull(block: suspend (WorkspaceAuthority) -> T): T? {
        currentAdmission()?.let { held -> return block(held) }
        val admitted = admitOrNull() ?: return null
        try {
            return withContext(AdmittedWrite(admitted)) { block(admitted) }
        } finally {
            release()
        }
    }

    override suspend fun <T> withExclusiveTransition(block: suspend () -> T): T {
        closeAdmissions()
        try {
            // Every writer admitted before admissions closed must finish before the workspace can
            // change; a switch that skipped this would let an old writer commit into a new engine.
            drained.first { it }
            return block()
        } finally {
            openAdmissions()
        }
    }

    private fun admit(): WorkspaceAuthority =
        synchronized(monitor) {
            engineReadinessRepository.readiness.value.requireWritable(
                writeFrozen = !admissionsOpen.value,
            )
            registerLocked()
        }

    private fun admitOrNull(): WorkspaceAuthority? =
        synchronized(monitor) {
            if (!engineReadinessRepository.readiness.value.isWritable(writeFrozen = !admissionsOpen.value)) {
                null
            } else {
                registerLocked()
            }
        }

    /** Records one admitted writer; callers must already hold [monitor]. */
    private fun registerLocked(): WorkspaceAuthority {
        val current =
            checkNotNull(engineReadinessRepository.workspaceAuthority.value) {
                "Ready engine published no workspace authority"
            }
        inFlight += 1
        drained.value = false
        return current
    }

    private fun release() {
        synchronized(monitor) {
            inFlight -= 1
            check(inFlight >= 0) { "Workspace mutation lease released more times than admitted" }
            if (inFlight == 0) {
                drained.value = true
            }
        }
    }

    private fun closeAdmissions() {
        synchronized(monitor) {
            check(admissionsOpen.value) { "Another workspace switch is already in progress" }
            admissionsOpen.value = false
        }
    }

    private fun openAdmissions() {
        synchronized(monitor) {
            admissionsOpen.value = true
        }
    }

    /**
     * Admission already held by this call chain, if any.
     *
     * Owning boundaries nest — the sync inbox drain writes through the markdown storage delegate,
     * and a memo save promotes media — so a nested writer must reuse the outer admission. Taking a
     * second one would deadlock against a transition that has already closed admissions while the
     * outer writer is still running and therefore still keeping the drain waiting.
     */
    private suspend fun currentAdmission(): WorkspaceAuthority? =
        coroutineContext[AdmittedWrite]?.authority

    /** Marks the call chain that already holds an admission from this lease. */
    private class AdmittedWrite(
        val authority: WorkspaceAuthority,
    ) : AbstractCoroutineContextElement(AdmittedWrite) {
        companion object Key : CoroutineContext.Key<AdmittedWrite>
    }
}
