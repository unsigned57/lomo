package com.lomo.ui.component.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ExitAnimationRegistry<T> {
    data class ExitEntry<T>(
        val item: T,
        val anchoredAfterKey: String?,
        val animationSettled: Boolean = false,
        val mutationCommitted: Boolean = false,
        val sourceAbsent: Boolean = false,
    ) {
        val exitPhase: LomoListExitPhase
            get() =
                if (animationSettled) {
                    LomoListExitPhase.Hidden
                } else {
                    LomoListExitPhase.Exiting
                }

        val canRemove: Boolean
            get() = animationSettled && mutationCommitted && sourceAbsent
    }

    private val _entries = MutableStateFlow<Map<String, ExitEntry<T>>>(emptyMap())
    val entries: StateFlow<Map<String, ExitEntry<T>>> = _entries.asStateFlow()

    fun beginExit(
        id: String,
        item: T,
        anchoredAfterKey: String?,
    ) {
        _entries.update { entries ->
            val previous = entries[id]
            entries + (
                id to
                    ExitEntry(
                        item = item,
                        anchoredAfterKey = anchoredAfterKey,
                        animationSettled = previous?.animationSettled ?: false,
                        mutationCommitted = previous?.mutationCommitted ?: false,
                        sourceAbsent = previous?.sourceAbsent ?: false,
                    )
            )
        }
    }

    fun markExitAnimationSettled(id: String) {
        updateEntry(id) { entry ->
            entry.copy(animationSettled = true)
        }
    }

    fun markExitMutationCommitted(id: String) {
        updateEntry(id) { entry ->
            entry.copy(mutationCommitted = true)
        }
    }

    fun markExitSourceAbsent(id: String) {
        updateEntry(id) { entry ->
            entry.copy(sourceAbsent = true)
        }
    }

    fun updateSourceKeys(sourceKeys: Set<String>) {
        _entries.update { entries ->
            entries
                .mapValues { (id, entry) ->
                    entry.copy(sourceAbsent = id !in sourceKeys)
                }
                .filterValues { entry -> !entry.canRemove }
        }
    }

    fun rollbackExit(id: String) {
        _entries.update { it - id }
    }

    fun clear() {
        _entries.value = emptyMap()
    }

    private fun updateEntry(
        id: String,
        transform: (ExitEntry<T>) -> ExitEntry<T>,
    ) {
        _entries.update { entries ->
            val entry = entries[id] ?: return@update entries
            val updatedEntry = transform(entry)
            if (updatedEntry.canRemove) {
                entries - id
            } else {
                entries + (id to updatedEntry)
            }
        }
    }
}
