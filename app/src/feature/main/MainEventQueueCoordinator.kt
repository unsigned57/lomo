package com.lomo.app.feature.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PendingUiEvent<T>(
    val id: Long,
    val payload: T,
)

class MainEventQueueCoordinator<T>(
    private val maxSize: Int = 64,
) {
    private var nextEventId = 0L
    private val _events = MutableStateFlow<List<PendingUiEvent<T>>>(emptyList())
    val events: StateFlow<List<PendingUiEvent<T>>> = _events.asStateFlow()

    fun enqueue(payload: T) {
        _events.update { events ->
            val next = PendingUiEvent(id = nextId(), payload = payload)
            (events + next).takeLast(maxSize)
        }
    }

    fun consume(eventId: Long) {
        _events.update { events -> events.filterNot { it.id == eventId } }
    }

    private fun nextId(): Long {
        nextEventId += 1
        return nextEventId
    }
}
