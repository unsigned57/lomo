package com.lomo.domain.repository

import com.lomo.domain.model.WorkspaceAuthority
import kotlinx.coroutines.flow.Flow

/**
 * Single admission point for every workspace mutation.
 *
 * A boolean "are writes allowed right now?" check cannot keep one writer per workspace: a caller
 * that passed the check is still allowed to write after a switch has begun, because nothing records
 * that it was admitted. Here admission and registration are one atomic step, so
 * [withExclusiveTransition] can close admissions and then wait for every writer it already admitted
 * to finish before the workspace changes underneath them.
 *
 * Writers acquire the lease at their owning boundary rather than repeating a readiness condition in
 * each use case.
 */
interface WorkspaceMutationLease {
    /** Authority writes are currently admitted under, or null when no workspace is writable. */
    val authority: Flow<WorkspaceAuthority?>

    /** True only while a mutation would currently be admitted. */
    fun isWritable(): Boolean

    /** Emits admissibility; used by drains that must re-request work after bootstrap or a switch. */
    fun isWritableFlow(): Flow<Boolean>

    /**
     * Runs [block] as an admitted workspace writer.
     *
     * Fails closed before [block] runs when the engine is not Ready or a transition is in progress.
     * The admission is released when [block] returns or throws.
     */
    suspend fun <T> withWrite(block: suspend (WorkspaceAuthority) -> T): T

    /**
     * Runs [block] as an admitted writer, or returns null without running it when a write would not
     * be admitted.
     *
     * For callers whose "workspace is not writable" outcome is a domain result rather than an
     * error; admission stays a single atomic step, so this is never a check followed by a write.
     */
    suspend fun <T : Any> withWriteOrNull(block: suspend (WorkspaceAuthority) -> T): T?

    /**
     * Closes admissions, waits for every in-flight writer to drain, then runs [block] as the sole
     * mutator. Admissions reopen when [block] returns or throws.
     */
    suspend fun <T> withExclusiveTransition(block: suspend () -> T): T
}
