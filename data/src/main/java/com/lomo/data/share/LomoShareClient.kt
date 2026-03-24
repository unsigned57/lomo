package com.lomo.data.share

import android.content.Context
import android.net.Uri
import com.lomo.domain.model.DiscoveredDevice
import com.lomo.domain.model.ShareAttachmentInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * HTTP client for sending memos to peer devices on the LAN.
 */
class LomoShareClient(
    context: Context,
    getPairingKeyHex: suspend () -> String?,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val client =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS // > 60s server timeout
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }

    private val prepareRequestExecutor =
        SharePrepareRequestExecutor(
            client = client,
            json = json,
            getPairingKeyHex = getPairingKeyHex,
        )

    private val transferRequestExecutor =
        ShareTransferRequestExecutor(
            context = context,
            client = client,
            json = json,
            getPairingKeyHex = getPairingKeyHex,
        )

    data class PreparedSession(
        val sessionToken: String?,
        val keyHex: String?,
    )

    /**
     * Phase 1: Send prepare request and wait for receiver's decision.
     * @return session token when accepted, null when rejected/timeout.
     */
    suspend fun prepare(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        senderName: String,
        attachments: List<ShareAttachmentInfo>,
        e2eEnabled: Boolean,
    ): Result<PreparedSession> =
        prepareRequestExecutor.prepare(
            device = device,
            content = content,
            timestamp = timestamp,
            senderName = senderName,
            attachments = attachments,
            e2eEnabled = e2eEnabled,
        )

    /**
     * Phase 2: Transfer memo content + attachments via multipart.
     * @param attachmentUris Map of filename to content URI for attachments within the memo.
     */
    suspend fun transfer(
        device: DiscoveredDevice,
        content: String,
        timestamp: Long,
        sessionToken: String,
        attachmentUris: Map<String, Uri>,
        e2eEnabled: Boolean,
        e2eKeyHex: String? = null,
    ): Boolean =
        transferRequestExecutor.transfer(
            device = device,
            content = content,
            timestamp = timestamp,
            sessionToken = sessionToken,
            attachmentUris = attachmentUris,
            e2eEnabled = e2eEnabled,
            e2eKeyHex = e2eKeyHex,
        )

    fun close() {
        client.close()
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val PING_TIMEOUT_MS = 3_000L
        const val REQUEST_TIMEOUT_MS = 70_000L
    }
}
