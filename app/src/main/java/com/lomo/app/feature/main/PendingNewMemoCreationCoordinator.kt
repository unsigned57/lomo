package com.lomo.app.feature.main

internal data class PendingNewMemoCreationRequest(
    val requestId: Long,
    val content: String,
)

internal class PendingNewMemoCreationCoordinator {
    private var nextRequestId = 1L

    var pendingRequest: PendingNewMemoCreationRequest? = null
        private set

    fun submit(content: String): PendingNewMemoCreationRequest? {
        if (pendingRequest != null) {
            return null
        }

        return PendingNewMemoCreationRequest(
            requestId = nextRequestId++,
            content = content,
        ).also { request ->
            pendingRequest = request
        }
    }

    fun consume(requestId: Long): PendingNewMemoCreationRequest? {
        val currentRequest = pendingRequest ?: return null
        if (currentRequest.requestId != requestId) {
            return null
        }

        pendingRequest = null
        return currentRequest
    }

    fun cancel(requestId: Long) {
        if (pendingRequest?.requestId == requestId) {
            pendingRequest = null
        }
    }
}
