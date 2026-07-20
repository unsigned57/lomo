package com.lomo.app.testing.fakes

import com.lomo.domain.repository.WriteFreezeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeWriteFreezeRepository : WriteFreezeRepository {
    private val _isFrozen = MutableStateFlow(false)
    override val isFrozen: StateFlow<Boolean> = _isFrozen.asStateFlow()

    override fun begin(): Boolean {
        _isFrozen.value = true
        return true
    }

    override fun end() {
        _isFrozen.value = false
    }
}
