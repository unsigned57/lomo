package com.lomo.data.share

import android.content.Context
import android.net.Uri
import com.lomo.domain.model.DiscoveredDevice
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import timber.log.Timber

internal class ShareTransferRequestExecutor(
    private val context: Context,
    private val client: HttpClient,
    private val json: Json,
    private val getPairingKeyHex: suspend () -> String?,
) {
    private val payloadBuilder = ShareTransferPayloadBuilder(context)

    suspend fun transfer(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        sessionToken: String,
        attachmentUris: Map<String, Uri>,
        e2eEnabled: Boolean,
        e2eKeyHex: String?,
    ): Boolean {
        val resolvedKey = resolveTransferKey(e2eEnabled, e2eKeyHex)
        if (e2eEnabled && resolvedKey == null) {
            Timber.tag(TAG).w("LAN share pairing code is not configured")
            return false
        }
        return runCatching {
            val payload =
                payloadBuilder.build(
                    content = content,
                    timestamp = timestamp,
                    sessionToken = sessionToken,
                    attachmentUris = attachmentUris,
                    e2eEnabled = e2eEnabled,
                    keyHex = resolvedKey,
                )
            try {
                val response =
                    submitTransferRequest(
                        device = device,
                        metadata = payload.toMetadata(sessionToken, timestamp, e2eEnabled),
                        attachments = payload.attachments,
                    )
                handleTransferResponse(response)
            } finally {
                payload.tempPayloadFiles.forEach { file ->
                    runCatching { file.delete() }
                }
            }
        }.getOrElse { error ->
            Timber.tag(TAG).e(error, "Transfer request failed")
            false
        }
    }

    private suspend fun resolveTransferKey(
        e2eEnabled: Boolean,
        e2eKeyHex: String?,
    ): String? =
        if (e2eEnabled) {
            resolvePrimaryKeyHex(e2eKeyHex)
                ?: resolvePrimaryKeyHex(getPairingKeyHex()?.trim())
        } else {
            null
        }

    private suspend fun submitTransferRequest(
        device: DiscoveredDevice,
        metadata: LomoShareServer.TransferMetadata,
        attachments: List<ShareTransferAttachment>,
    ): TransferHttpResponse {
        val response =
            client.submitFormWithBinaryData(
                url = "http://${device.host}:${device.port}/share/transfer",
                formData =
                    formData {
                        append(
                            METADATA_FORM_KEY,
                            json.encodeToString(LomoShareServer.TransferMetadata.serializer(), metadata),
                        )
                        attachments.forEachIndexed { index, attachment ->
                            appendInput(
                                key = "attachment_$index",
                                headers = attachmentHeaders(attachment),
                                size = attachment.payloadSizeBytes,
                            ) {
                                attachmentSourceFor(attachment)
                            }
                        }
                    },
            )
        return TransferHttpResponse(response.status, response.bodyAsText())
    }

    private fun attachmentHeaders(attachment: ShareTransferAttachment): Headers =
        Headers.build {
            append(HttpHeaders.ContentType, attachment.contentType)
            append(HttpHeaders.ContentDisposition, "filename=\"${attachment.name}\"")
        }

    private fun attachmentSourceFor(attachment: ShareTransferAttachment) =
        when {
            attachment.payloadFile != null -> {
                attachment.payloadFile.inputStream().asSource().buffered()
            }

            attachment.sourceUri != null -> {
                context.contentResolver
                    .openInputStream(attachment.sourceUri)
                    ?.asSource()
                    ?.buffered()
                    ?: throw IllegalStateException("Cannot open attachment: ${attachment.name}")
            }

            else -> {
                throw IllegalStateException("Attachment has no payload source: ${attachment.name}")
            }
        }

    private fun handleTransferResponse(response: TransferHttpResponse): Boolean {
        if (response.status != HttpStatusCode.OK) {
            Timber.tag(TAG).w("Transfer failed with status=${response.status.value}: ${response.bodyText}")
            return false
        }
        val body = json.decodeFromString<LomoShareServer.TransferResponse>(response.bodyText)
        Timber.tag(TAG).d("Transfer response: success=${body.success}")
        return body.success
    }

    private data class TransferHttpResponse(
        val status: HttpStatusCode,
        val bodyText: String,
    )

    private companion object {
        private const val METADATA_FORM_KEY = "metadata"
        private const val TAG = "LomoShareClient"
    }
}
