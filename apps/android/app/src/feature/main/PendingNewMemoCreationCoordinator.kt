package com.lomo.app.feature.main

import com.lomo.app.feature.memo.MemoEditorSubmissionId

internal data class PendingNewMemoCreationRequest(
    val requestId: Long,
    val submissionId: MemoEditorSubmissionId,
    val content: String,
    val geoLocation: String? = null,
    val timestampMillis: Long? = null,
)

internal class PendingNewMemoCreationCoordinator {
    private var nextRequestId = 1L

    var pendingRequest: PendingNewMemoCreationRequest? = null
        private set

    fun submit(
        submissionId: MemoEditorSubmissionId,
        content: String,
        geoLocation: String? = null,
        timestampMillis: Long? = null,
    ): PendingNewMemoCreationRequest? {
        if (pendingRequest != null) {
            return null
        }

        return PendingNewMemoCreationRequest(
            requestId = nextRequestId++,
            submissionId = submissionId,
            content = content,
            geoLocation = geoLocation,
            timestampMillis = timestampMillis,
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
