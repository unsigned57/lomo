package com.lomo.app.feature.main

internal data class PendingNewMemoCreationRequest(
    val requestId: Long,
    val content: String,
    val geoLocation: String? = null,
)

internal class PendingNewMemoCreationCoordinator {
    private var nextRequestId = 1L

    var pendingRequest: PendingNewMemoCreationRequest? = null
        private set

    fun submit(content: String, geoLocation: String? = null): PendingNewMemoCreationRequest? {
        if (pendingRequest != null) {
            return null
        }

        return PendingNewMemoCreationRequest(
            requestId = nextRequestId++,
            content = content,
            geoLocation = geoLocation,
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
