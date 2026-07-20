package com.lomo.data.repository

import com.lomo.domain.repository.WriteFreezeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local write freeze used by workspace root switch.
 *
 * Not durable across process death; switch either completes or leaves the previous selection
 * authoritative after restart.
 */
class ProcessWriteFreezeRepository : WriteFreezeRepository {
    private val frozen = AtomicBoolean(false)
    private val _isFrozen = MutableStateFlow(false)
    override val isFrozen: StateFlow<Boolean> = _isFrozen.asStateFlow()

    override fun begin(): Boolean {
        if (!frozen.compareAndSet(false, true)) {
            return false
        }
        _isFrozen.value = true
        return true
    }

    override fun end() {
        frozen.set(false)
        _isFrozen.value = false
    }
}
