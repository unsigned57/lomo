package com.lomo.data.diagnostics

import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/** Bounded newest-first diagnostics window. Timber receives the same structured summary. */
class RingBufferEngineDiagnosticsRecorder(private val capacity: Int = DEFAULT_CAPACITY) : EngineDiagnosticsRecorder {
    init {
        require(capacity > 0) { "Diagnostics capacity must be positive" }
    }

    private val _events = MutableStateFlow<List<EngineDiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<EngineDiagnosticEvent>> = _events.asStateFlow()

    override fun record(event: EngineDiagnosticEvent) {
        _events.update { current -> (listOf(event) + current).take(capacity) }
        val line = event.summary()
        when (event) {
            is EngineDiagnosticEvent.Rejected -> Timber.tag(LOG_TAG).w(line)
            is EngineDiagnosticEvent.Stalled -> Timber.tag(LOG_TAG).w(line)
            is EngineDiagnosticEvent.Committed -> Timber.tag(LOG_TAG).i(line)
        }
    }

    companion object {
        /** Filter with `adb logcat -s LomoEngine`. */
        const val LOG_TAG = "LomoEngine"

        private const val DEFAULT_CAPACITY = 100
    }
}
