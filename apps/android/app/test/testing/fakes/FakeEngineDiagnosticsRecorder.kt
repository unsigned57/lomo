package com.lomo.app.testing.fakes

import com.lomo.domain.model.EngineDiagnosticEvent
import com.lomo.domain.model.EngineDiagnosticsRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class FakeEngineDiagnosticsRecorder : EngineDiagnosticsRecorder {
    private val recorded = MutableStateFlow<List<EngineDiagnosticEvent>>(emptyList())
    override val events = recorded.asStateFlow()

    override fun record(event: EngineDiagnosticEvent) {
        recorded.update { listOf(event) + it }
    }
}
