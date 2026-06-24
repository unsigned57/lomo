package com.lomo.ui.component.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ExitAnimationRegistry<T> {
    data class ExitEntry<T>(
        val item: T,
        val anchoredAfterKey: String?
    )

    private val _entries = MutableStateFlow<Map<String, ExitEntry<T>>>(emptyMap())
    val entries: StateFlow<Map<String, ExitEntry<T>>> = _entries.asStateFlow()

    fun beginExit(id: String, item: T, anchoredAfterKey: String?) {
        _entries.update { it + (id to ExitEntry(item, anchoredAfterKey)) }
    }

    fun settleExit(id: String) {
        _entries.update { it - id }
    }

    fun clear() {
        _entries.value = emptyMap()
    }
}
