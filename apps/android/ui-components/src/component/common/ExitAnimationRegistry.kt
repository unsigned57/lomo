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

    data class ExitRequest<T>(val id: String, val item: T, val anchoredAfterKey: String?)

    fun beginExit(id: String, item: T, anchoredAfterKey: String?) {
        beginExits(listOf(ExitRequest(id, item, anchoredAfterKey)))
    }

    fun beginExits(items: List<ExitRequest<T>>) {
        _entries.update { entries ->
            entries.toMutableMap().apply {
                items.forEach { request ->
                    val previous = get(request.id)
                    put(
                        request.id,
                        ExitEntry(
                            item = request.item,
                            anchoredAfterKey = request.anchoredAfterKey,
                            animationSettled = previous?.animationSettled == true,
                            mutationCommitted = previous?.mutationCommitted == true,
                            sourceAbsent = previous?.sourceAbsent == true,
                        ),
                    )
                }
            }
        }
    }

    fun markExitAnimationSettled(id: String) {
        updateEntries(setOf(id)) { entry ->
            entry.copy(animationSettled = true)
        }
    }

    fun markExitMutationCommitted(id: String) {
        markExitMutationsCommitted(setOf(id))
    }

    fun markExitMutationsCommitted(ids: Set<String>) {
        updateEntries(ids) { entry -> entry.copy(mutationCommitted = true) }
    }

    fun markExitSourceAbsent(id: String) {
        updateEntries(setOf(id)) { entry ->
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
        rollbackExits(setOf(id))
    }

    fun rollbackExits(ids: Set<String>) {
        _entries.update { it - ids }
    }

    fun clear() {
        _entries.value = emptyMap()
    }

    private fun updateEntries(ids: Set<String>, transform: (ExitEntry<T>) -> ExitEntry<T>) {
        _entries.update { entries ->
            entries.toMutableMap().apply {
                ids.forEach { id ->
                    val entry = get(id)
                    if (entry != null) {
                        val updated = transform(entry)
                        if (updated.canRemove) remove(id) else put(id, updated)
                    }
                }
            }
        }
    }
}
