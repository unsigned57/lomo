package com.lomo.app.feature.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingUiEvent<T>(
    val id: Long,
    val payload: T,
)

sealed interface UiEventEnqueueResult {
    data class Accepted(val eventId: Long) : UiEventEnqueueResult
    data class Rejected(val reason: UiEventQueueRejection) : UiEventEnqueueResult
}

enum class UiEventQueueRejection { CapacityReached, IdSpaceExhausted }

private const val DEFAULT_UI_EVENT_QUEUE_CAPACITY = 64
private const val MAX_UI_EVENT_QUEUE_CAPACITY = 1024

class UiEventQueueCoordinator<T>(
    private val maxSize: Int = DEFAULT_UI_EVENT_QUEUE_CAPACITY,
) {
    init {
        require(maxSize in 1..MAX_UI_EVENT_QUEUE_CAPACITY) {
            "UI event queue capacity must be within 1..$MAX_UI_EVENT_QUEUE_CAPACITY"
        }
    }

    private val transitionLock = Any()
    private var nextEventId = 0L
    private val _events = MutableStateFlow<List<PendingUiEvent<T>>>(emptyList())
    val events: StateFlow<List<PendingUiEvent<T>>> = _events.asStateFlow()

    /** Rejected commands remain with the producer; accepted commands stay until acknowledged. */
    fun enqueue(payload: T): UiEventEnqueueResult =
        synchronized(transitionLock) {
            val events = _events.value
            when {
                events.size >= maxSize -> UiEventEnqueueResult.Rejected(UiEventQueueRejection.CapacityReached)
                nextEventId == Long.MAX_VALUE -> UiEventEnqueueResult.Rejected(UiEventQueueRejection.IdSpaceExhausted)
                else -> {
                    val id = nextEventId + 1L
                    nextEventId = id
                    _events.value = events + PendingUiEvent(id, payload)
                    UiEventEnqueueResult.Accepted(id)
                }
            }
        }

    fun consume(eventId: Long) {
        synchronized(transitionLock) {
            _events.value = _events.value.filterNot { it.id == eventId }
        }
    }
}
