package com.lomo.ui.component.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EnterAnimationRegistry {
    private val _activeEnters = MutableStateFlow<Set<String>>(emptySet())
    val activeEnters: StateFlow<Set<String>> = _activeEnters.asStateFlow()

    fun beginEnter(id: String) {
        _activeEnters.update { it + id }
    }

    fun settleEnter(id: String) {
        _activeEnters.update { it - id }
    }

    fun clear() {
        _activeEnters.value = emptySet()
    }
}
