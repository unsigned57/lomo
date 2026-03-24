package com.lomo.data.share

import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.SharePayload
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ktor-based embedded HTTP server that handles incoming share requests from LAN peers.
 */
@OptIn(ExperimentalUuidApi::class)
class LomoShareServer {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val stateLock = Any()

    private data class PendingApproval(
        val deferred: CompletableDeferred<Boolean>,
    )

    private data class ApprovedSession(
        val requestHash: String,
        val attachmentNames: Set<String>,
        val e2eEnabled: Boolean,
        val createdAtMs: Long,
    )

    private var pendingApproval: PendingApproval? = null
    private val approvedSessions = mutableMapOf<String, ApprovedSession>()
    private val requestValidator = ShareRequestValidator()
    private val authValidator = ShareAuthenticationValidator()

    // Callback to notify UI of incoming share request
    var onIncomingPrepare: ((SharePayload) -> Unit)? = null

    // Callback to save attachments; returns the saved file path
    var onSaveAttachment: ShareAttachmentSaver? = null

    // Callback to save the received memo
    var onSaveMemo: ShareMemoSaver? = null

    // Callback to resolve local pairing key for request authentication
    var getPairingKeyHex: (suspend () -> String?)? = null

    // Callback to check whether receiver currently requires E2E mode.
    var isE2eEnabled: (suspend () -> Boolean)? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun start(port: Int = 0): Int {
        val prepareHandler = createPrepareHandler()
        val transferHandler = createTransferHandler()
        server = buildShareServer(port, json, prepareHandler, transferHandler).start(wait = false)

        val finalPort = resolveBoundPort(server, port)
        Timber.tag(TAG).d("Server started on port $finalPort")
        return finalPort
    }

    fun stop() {
        synchronized(stateLock) {
            pendingApproval?.deferred?.complete(false)
            pendingApproval = null
            approvedSessions.clear()
        }
        authValidator.clearNonces()
        server?.stop(SERVER_STOP_GRACE_MS, SERVER_STOP_TIMEOUT_MS)
        server = null
        Timber.tag(TAG).d("Server stopped")
    }

    fun acceptIncoming() {
        synchronized(stateLock) {
            pendingApproval?.deferred?.complete(true)
        }
    }

    fun rejectIncoming() {
        synchronized(stateLock) {
            pendingApproval?.deferred?.complete(false)
        }
    }

    private fun createTransferHandler(): LomoShareTransferHandler =
        LomoShareTransferHandler(
            json = json,
            isE2eEnabled = { this@LomoShareServer.isE2eEnabled?.invoke() ?: true },
            validateTransferMetadata = requestValidator::validateTransferMetadata,
            validateTransferAuthentication = { metadata ->
                authValidator.validateTransferAuthentication(metadata) {
                    getPairingKeyHex?.invoke()
                }
            },
            consumeApprovedSession = ::consumeApprovedSession,
            buildRequestHash = ::buildRequestHash,
            onSaveAttachment = { onSaveAttachment },
            onSaveMemo = { onSaveMemo },
        )

    private fun createPrepareHandler(): SharePrepareRequestProcessor =
        SharePrepareRequestProcessor(
            json = json,
            isE2eEnabled = { this@LomoShareServer.isE2eEnabled?.invoke() ?: true },
            validatePrepareRequest = requestValidator::validatePrepareRequest,
            validatePrepareAuthentication = { request ->
                authValidator.validatePrepareAuthentication(request) {
                    getPairingKeyHex?.invoke()
                }
            },
            onIncomingPrepare = { onIncomingPrepare },
            reserveApproval = ::reserveApproval,
            storeApprovedSession = ::storeApprovedSession,
            clearPendingApproval = {
                synchronized(stateLock) {
                    pendingApproval = null
                }
            },
            buildRequestHash = ::buildRequestHash,
            approvalTimeoutMs = APPROVAL_TIMEOUT_MS,
        )

    private fun reserveApproval(deferred: CompletableDeferred<Boolean>): Boolean =
        synchronized(stateLock) {
            cleanupExpiredSessionsLocked()
            if (pendingApproval != null) {
                false
            } else {
                pendingApproval = PendingApproval(deferred)
                true
            }
        }

    private fun storeApprovedSession(
        requestHash: String,
        attachmentNames: Set<String>,
        e2eEnabled: Boolean,
    ): String {
        val token = Uuid.random().toString()
        synchronized(stateLock) {
            cleanupExpiredSessionsLocked()
            approvedSessions[token] =
                ApprovedSession(
                    requestHash = requestHash,
                    attachmentNames = attachmentNames,
                    e2eEnabled = e2eEnabled,
                    createdAtMs = System.currentTimeMillis(),
                )
        }
        return token
    }

    private fun consumeApprovedSession(
        sessionToken: String,
        requestHash: String,
        attachmentNames: Set<String>,
        e2eEnabled: Boolean,
    ): Boolean =
        synchronized(stateLock) {
            cleanupExpiredSessionsLocked()
            val existing = approvedSessions[sessionToken] ?: return@synchronized false
            if (existing.requestHash != requestHash ||
                existing.attachmentNames != attachmentNames ||
                existing.e2eEnabled != e2eEnabled
            ) {
                false
            } else {
                approvedSessions.remove(sessionToken)
                true
            }
        }

    private fun cleanupExpiredSessionsLocked(nowMs: Long = System.currentTimeMillis()) {
        approvedSessions.entries.removeIf { (_, session) ->
            nowMs - session.createdAtMs > SESSION_TTL_MS
        }
    }

    private fun buildRequestHash(
        content: String,
        timestamp: Long,
        attachmentNames: List<String>,
        e2eEnabled: Boolean,
    ): String {
        val canonicalNames = attachmentNames.map { it.trim() }.sorted()
        val raw =
            buildString {
                append(if (e2eEnabled) "e2e" else "open")
                append('\n')
                append(timestamp)
                append('\n')
                append(content)
                append('\n')
                canonicalNames.forEach { append(it).append('\n') }
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- Protocol Data Classes ---

    @Serializable
    data class PrepareRequest(
        val senderName: String,
        val encryptedContent: String,
        val contentNonce: String,
        val timestamp: Long,
        val e2eEnabled: Boolean = true,
        val attachments: List<AttachmentInfo> = emptyList(),
        val authTimestampMs: Long = 0L,
        val authNonce: String = "",
        val authSignature: String = "",
    ) {
        fun toSharePayload(decryptedContent: String) =
            SharePayload(
                content = decryptedContent,
                timestamp = timestamp,
                senderName = senderName,
                attachments =
                    attachments.map {
                        ShareAttachmentInfo(name = it.name, type = it.type, size = it.size)
                    },
            )
    }

    @Serializable
    data class AttachmentInfo(
        val name: String,
        val type: String,
        val size: Long,
    )

    @Serializable
    data class PrepareResponse(
        val accepted: Boolean,
        val sessionToken: String? = null,
    )

    @Serializable
    data class TransferMetadata(
        val sessionToken: String,
        val encryptedContent: String,
        val contentNonce: String,
        val timestamp: Long,
        val e2eEnabled: Boolean = true,
        val attachmentNames: List<String> = emptyList(),
        val attachmentNonces: Map<String, String> = emptyMap(),
        val authTimestampMs: Long = 0L,
        val authNonce: String = "",
        val authSignature: String = "",
    )

    @Serializable
    data class TransferResponse(
        val success: Boolean,
    )

    companion object {
        private const val TAG = "LomoShareServer"
        private const val APPROVAL_TIMEOUT_MS = 60_000L // 60 seconds to approve
        private const val SERVER_STOP_GRACE_MS = 500L
        private const val SERVER_STOP_TIMEOUT_MS = 1_000L
        private const val SESSION_TTL_MS = 120_000L
    }
}

private const val BOUND_PORT_POLL_ATTEMPTS = 5
private const val BOUND_PORT_POLL_DELAY_MS = 100L
private const val SERVER_HOST = "0.0.0.0"

private fun buildShareServer(
    port: Int,
    jsonSerializer: Json,
    prepareHandler: SharePrepareRequestProcessor,
    transferHandler: LomoShareTransferHandler,
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
    embeddedServer(CIO, port = port, host = SERVER_HOST) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }

        routing {
            get("/share/ping") {
                call.respondText("pong", ContentType.Text.Plain)
            }
            post("/share/prepare") {
                prepareHandler.handle(call)
            }
            post("/share/transfer") {
                transferHandler.handle(call)
            }
        }
    }

private suspend fun resolveBoundPort(
    server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>?,
    fallbackPort: Int,
): Int {
    var resolvedPort: Int? = null
    repeat(BOUND_PORT_POLL_ATTEMPTS) {
        resolvedPort =
            server
                ?.engine
                ?.resolvedConnectors()
                ?.firstOrNull()
                ?.port
        if (resolvedPort != null && resolvedPort != 0) {
            return resolvedPort
        }
        delay(BOUND_PORT_POLL_DELAY_MS)
    }
    return resolvedPort ?: fallbackPort
}
