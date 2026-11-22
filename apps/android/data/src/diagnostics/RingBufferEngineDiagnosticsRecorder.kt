package com.lomo.data.diagnostics

import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import com.lomo.domain.model.isError
import com.lomo.domain.model.redactedExtraLines
import com.lomo.domain.model.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Bounded newest-first diagnostics window. Timber receives the same structured summary.
 *
 * Errors (`Rejected` / `Stalled`) occupy a dedicated retention partition so routine `Committed`
 * operations cannot evict earlier failures before the user opens the diagnostics dialog.
 */
class RingBufferEngineDiagnosticsRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val errorCapacity: Int = (capacity / 2).coerceAtLeast(1),
    private val clock: () -> Long = System::currentTimeMillis,
) : EngineDiagnosticsRecorder {
    init {
        require(capacity > 0) { "Diagnostics capacity must be positive" }
        require(errorCapacity in 1..capacity) { "Diagnostics error capacity must be within 1..capacity" }
    }

    private val lock = Any()
    private val errorEvents = ArrayDeque<EngineDiagnosticEvent>(errorCapacity)
    private val infoCapacity = (capacity - errorCapacity).coerceAtLeast(0)
    private val infoEvents = ArrayDeque<EngineDiagnosticEvent>(infoCapacity)

    private val _events = MutableStateFlow<List<EngineDiagnosticEvent>>(emptyList())
    override val events: StateFlow<List<EngineDiagnosticEvent>> = _events.asStateFlow()

    override fun record(event: EngineDiagnosticEvent) {
        val stamped = event.withTimestampIfUnset(clock())
        synchronized(lock) {
            if (stamped.isError) {
                errorEvents.addFirst(stamped)
                while (errorEvents.size > errorCapacity) {
                    errorEvents.removeLast()
                }
            } else if (infoCapacity > 0) {
                infoEvents.addFirst(stamped)
                while (infoEvents.size > infoCapacity) {
                    infoEvents.removeLast()
                }
            }
            _events.value = mergeNewestFirst()
        }
        val extra = stamped.redactedExtraLines()
        val line =
            if (extra.isEmpty()) {
                stamped.summary()
            } else {
                "${stamped.summary()} | ${extra.joinToString(" | ")}"
            }
        when (stamped) {
            is EngineDiagnosticEvent.Rejected -> Timber.tag(LOG_TAG).w(line)
            is EngineDiagnosticEvent.Stalled -> Timber.tag(LOG_TAG).w(line)
            is EngineDiagnosticEvent.Committed -> Timber.tag(LOG_TAG).i(line)
        }
    }

    override fun clear() {
        synchronized(lock) {
            errorEvents.clear()
            infoEvents.clear()
            _events.value = emptyList()
        }
    }

    private fun mergeNewestFirst(): List<EngineDiagnosticEvent> = (errorEvents.asSequence() + infoEvents.asSequence())
        .sortedByDescending { it.recordedAtMillis }
        .take(capacity)
        .toList()

    private fun EngineDiagnosticEvent.withTimestampIfUnset(nowMillis: Long): EngineDiagnosticEvent {
        if (recordedAtMillis > 0L) return this
        return when (this) {
            is EngineDiagnosticEvent.Committed -> copy(recordedAtMillis = nowMillis)
            is EngineDiagnosticEvent.Rejected -> copy(recordedAtMillis = nowMillis)
            is EngineDiagnosticEvent.Stalled -> copy(recordedAtMillis = nowMillis)
        }
    }

    companion object {
        /** Filter with `adb logcat -s LomoEngine`. */
        const val LOG_TAG = "LomoEngine"

        private const val DEFAULT_CAPACITY = 100
    }
}
