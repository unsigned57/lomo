package com.lomo.data.diagnostics

import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.detail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * Bounded newest-first diagnostics window, mirrored to the system log.
 *
 * The cap is the point: a diagnostics channel that grows without limit is a leak, and one that
 * drops the newest event is useless right when a failure is being reproduced.
 *
 * The log sink is the other half of the same property. An in-process window can only be read by a
 * surface the app manages to render, so a rejection that happens on a background job, before the
 * UI exists, or while the screen is unreachable was observable only in principle. Writing every
 * recorded event to the system log makes the channel readable with `adb logcat -s [LOG_TAG]`
 * without a build flag to forget to enable. Nothing is uploaded and nothing is persisted by this
 * class; the raw diagnostic never reaches the user-facing message.
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
        val line = "${event.label} · ${event.durationMillis}ms · ${event.detail()}"
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
