package com.lomo.domain.testing.fakes

import com.lomo.domain.repository.WriteFreezeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeWriteFreezeRepository : WriteFreezeRepository {
    private val _isFrozen = MutableStateFlow(false)
    override val isFrozen: StateFlow<Boolean> = _isFrozen.asStateFlow()
    var beginCount = 0
        private set
    var endCount = 0
        private set
    var beginResult: Boolean = true

    override fun begin(): Boolean {
        beginCount += 1
        if (!beginResult) return false
        _isFrozen.value = true
        return true
    }

    override fun end() {
        endCount += 1
        _isFrozen.value = false
    }
}
