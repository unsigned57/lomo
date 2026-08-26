package com.lomo.data.diagnostics

import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bounded newest-first diagnostics window.
 *
 * The cap is the point: a diagnostics channel that grows without limit is a leak, and one that
 * drops the newest event is useless right when a failure is being reproduced.
 */
class RingBufferEngineDiagnosticsRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
) : EngineDiagnosticsRecorder {
    init {
        require(capacity > 0) { "Diagnostics capacity must be positive" }
    }

    private val _events = MutableStateFlow<List<EngineDiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<EngineDiagnosticEvent>> = _events.asStateFlow()

    override fun record(event: EngineDiagnosticEvent) {
        _events.update { current -> (listOf(event) + current).take(capacity) }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 100
    }
}
