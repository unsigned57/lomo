package com.lomo.ui.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reusable manager for optimistic UI mutations on a Paging list.
 *
 * Encapsulates the two-phase "fade-out then collapse" pattern:
 *   1. Mark item as visible-but-deleting (isHidden = false) — triggers fade animation.
 *   2. After [fadeDelayMs], mark item as hidden (isHidden = true) — collapses the row.
 *   3. Execute the real [action] (suspend call to repository).
 *   4. After [clearDelayMs], remove the mutation so Paging data takes over.
 *
 * Usage:
 * ```kotlin
 * private val mutations = OptimisticMutationManager(viewModelScope)
 * val pendingMutations = mutations.state
 *
 * fun deleteMemo(memo: Memo) = mutations.delete(memo.id) { repository.deleteMemo(memo) }
 * ```
 */
class OptimisticMutationManager(
    private val scope: CoroutineScope,
    private val fadeDelayMs: Long = 300L,
    private val clearDelayMs: Long = 3_000L,
) {
    data class MutationState(
        val isHidden: Boolean = false,
    )

    private val _state = MutableStateFlow<Map<String, MutationState>>(emptyMap())
    val state: StateFlow<Map<String, MutationState>> = _state.asStateFlow()

    /**
     * Perform an optimistic delete/remove for [id].
     * Calls [action] after the fade animation delay.
     * Returns the launched [Job] so callers can cancel if needed.
     *
     * @param id         Unique key for the item being mutated.
     * @param action     Suspend function that performs the real operation (e.g. repository call).
     * @param onError    Called if [action] throws; mutation is rolled back.
     */
    fun delete(
        id: String,
        onError: ((Throwable) -> Unit)? = null,
        action: suspend () -> Unit,
    ): Job =
        scope.launch {
            // Phase 1: mark visible-but-deleting
            _state.update { it + (id to MutationState(isHidden = false)) }
            try {
                delay(fadeDelayMs)
                // Phase 2: collapse the row
                _state.update { it + (id to MutationState(isHidden = true)) }
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it - id }
                throw e
            } catch (e: Exception) {
                _state.update { it - id }
                onError?.invoke(e)
            } finally {
                delay(clearDelayMs)
                _state.update { it - id }
            }
        }

    /** Remove a mutation immediately (e.g. on rollback). */
    fun clear(id: String) {
        _state.update { it - id }
    }
}
