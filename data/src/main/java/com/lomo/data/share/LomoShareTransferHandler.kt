package com.lomo.data.share

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import java.io.File

internal class LomoShareTransferHandler(
    json: Json,
    isE2eEnabled: suspend () -> Boolean,
    validateTransferMetadata: (LomoShareServer.TransferMetadata) -> String?,
    validateTransferAuthentication: suspend (LomoShareServer.TransferMetadata) -> ShareAuthValidation,
    consumeApprovedSession: (
        sessionToken: String,
        requestHash: String,
        attachmentNames: Set<String>,
        e2eEnabled: Boolean,
    ) -> Boolean,
    buildRequestHash: (
        content: String,
        timestamp: Long,
        attachmentNames: List<String>,
        e2eEnabled: Boolean,
    ) -> String,
    onSaveAttachment: () -> (suspend (name: String, type: String, payloadFile: File) -> String?)?,
    onDeleteAttachment: () -> (suspend (savedPath: String, type: String) -> Unit)?,
    onSaveMemo: () -> (suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit)?,
) {
    private val processor =
        ShareTransferRequestProcessor(
            json = json,
            isE2eEnabled = isE2eEnabled,
            validateTransferMetadata = validateTransferMetadata,
            validateTransferAuthentication = validateTransferAuthentication,
            consumeApprovedSession = consumeApprovedSession,
            buildRequestHash = buildRequestHash,
            onSaveAttachment = onSaveAttachment,
            onDeleteAttachment = onDeleteAttachment,
            onSaveMemo = onSaveMemo,
        )

    suspend fun handle(call: ApplicationCall) {
        processor.handle(call)
    }
}
