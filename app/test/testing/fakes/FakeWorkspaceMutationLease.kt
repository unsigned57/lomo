package com.lomo.app.testing.fakes

import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.isWritable
import com.lomo.domain.model.requireWritable
import com.lomo.domain.repository.EngineReadinessRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Admission registry that drains like the production lease, backed by [engineReadiness] so
 * fail-closed messages match production.
 */
class FakeWorkspaceMutationLease(
    private val engineReadiness: EngineReadinessRepository = FakeEngineReadinessRepository(),
) : WorkspaceMutationLease {
    private val admissionsOpen = MutableStateFlow(true)
    private val drained = MutableStateFlow(true)
    private val monitor = Any()
    private var inFlight = 0

    var transitionCount = 0
        private set

    override val authority: Flow<WorkspaceAuthority?> = engineReadiness.workspaceAuthority

    override fun isWritable(): Boolean =
        synchronized(monitor) {
            engineReadiness.readiness.value.isWritable(writeFrozen = !admissionsOpen.value)
        }

    override fun isWritableFlow(): Flow<Boolean> =
        combine(engineReadiness.readiness, admissionsOpen) { readiness, open ->
            readiness.isWritable(writeFrozen = !open)
        }

    override suspend fun <T> withWrite(block: suspend (WorkspaceAuthority) -> T): T {
        val admitted = admit()
        try {
            return block(admitted)
        } finally {
            release()
        }
    }

    override suspend fun <T : Any> withWriteOrNull(block: suspend (WorkspaceAuthority) -> T): T? {
        val admitted = admitOrNull() ?: return null
        try {
            return block(admitted)
        } finally {
            release()
        }
    }

    override suspend fun <T> withExclusiveTransition(block: suspend () -> T): T {
        synchronized(monitor) {
            check(admissionsOpen.value) { "Another workspace switch is already in progress" }
            admissionsOpen.value = false
            transitionCount += 1
        }
        try {
            drained.first { it }
            return block()
        } finally {
            synchronized(monitor) { admissionsOpen.value = true }
        }
    }

    private fun admit(): WorkspaceAuthority =
        synchronized(monitor) {
            engineReadiness.readiness.value.requireWritable(writeFrozen = !admissionsOpen.value)
            registerLocked()
        }

    private fun admitOrNull(): WorkspaceAuthority? =
        synchronized(monitor) {
            if (!engineReadiness.readiness.value.isWritable(writeFrozen = !admissionsOpen.value)) {
                null
            } else {
                registerLocked()
            }
        }

    private fun registerLocked(): WorkspaceAuthority {
        inFlight += 1
        drained.value = false
        return engineReadiness.workspaceAuthority.value
            ?: WorkspaceAuthority(workspaceId = "fake-workspace", generation = 0)
    }

    private fun release() {
        synchronized(monitor) {
            inFlight -= 1
            if (inFlight == 0) drained.value = true
        }
    }
}
