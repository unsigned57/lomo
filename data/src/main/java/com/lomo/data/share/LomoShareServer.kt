package com.lomo.data.share

import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.SharePayload
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ktor-based embedded HTTP server that handles incoming share requests from LAN peers.
 */
@OptIn(ExperimentalUuidApi::class)
class LomoShareServer {
    companion object {
        private const val TAG = "LomoShareServer"
        private const val APPROVAL_TIMEOUT_MS = 60_000L // 60 seconds to approve
        private const val SESSION_TTL_MS = 120_000L
    }

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
    var onSaveAttachment: (suspend (name: String, type: String, payloadFile: File) -> String?)? = null

    // Callback to save the received memo
    var onSaveMemo: (suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit)? = null

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
        val transferHandler = createTransferHandler()
        server =
            embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    json(json)
                }

                routing {
                    get("/share/ping") {
                        call.respondText("pong", ContentType.Text.Plain)
                    }

                    // Phase 1: Prepare - sender asks if receiver will accept
                    post("/share/prepare") {
                        try {
                            val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                            if (contentLength != null && contentLength > ShareTransferLimits.MAX_PREPARE_BODY_CHARS) {
                                call.respond(HttpStatusCode.PayloadTooLarge, "Prepare payload too large")
                                return@post
                            }

                            val body = call.receiveText()
                            if (body.length > ShareTransferLimits.MAX_PREPARE_BODY_CHARS) {
                                call.respond(HttpStatusCode.PayloadTooLarge, "Prepare payload too large")
                                return@post
                            }
                            val request = json.decodeFromString<PrepareRequest>(body)
                            val localE2eEnabled = isE2eEnabled?.invoke() ?: true
                            if (request.e2eEnabled != localE2eEnabled) {
                                call.respond(HttpStatusCode.PreconditionFailed, "Encryption mode mismatch")
                                return@post
                            }
                            if (!request.e2eEnabled) {
                                Timber.tag(TAG).w("Received OPEN mode prepare request (unauthenticated)")
                            }
                            val validationError = requestValidator.validatePrepareRequest(request)
                            if (validationError != null) {
                                call.respond(HttpStatusCode.BadRequest, validationError)
                                return@post
                            }
                            val authValidation =
                                authValidator.validatePrepareAuthentication(request) {
                                    getPairingKeyHex?.invoke()
                                }
                            if (!authValidation.ok) {
                                call.respond(authValidation.status, authValidation.message)
                                return@post
                            }
                            val keyHex = authValidation.keyHex
                            if (request.e2eEnabled && keyHex.isNullOrBlank()) {
                                call.respond(HttpStatusCode.PreconditionFailed, "Missing pairing key")
                                return@post
                            }
                            val decryptedContent =
                                if (request.e2eEnabled) {
                                    ShareCryptoUtils.decryptText(
                                        keyHex = keyHex ?: "",
                                        ciphertextBase64 = request.encryptedContent,
                                        nonceBase64 = request.contentNonce,
                                        aad = "memo-content",
                                    )
                                } else {
                                    request.encryptedContent
                                }
                            if (decryptedContent == null) {
                                call.respond(HttpStatusCode.Unauthorized, "Cannot decrypt prepare content")
                                return@post
                            }
                            if (decryptedContent.length > ShareTransferLimits.maxMemoChars(e2eEnabled = false)) {
                                call.respond(HttpStatusCode.PayloadTooLarge, "Memo content too large")
                                return@post
                            }

                            val payload = request.toSharePayload(decryptedContent)
                            val normalizedNames = request.attachments.map { it.name.trim() }
                            val requestHash =
                                buildRequestHash(
                                    content = decryptedContent,
                                    timestamp = request.timestamp,
                                    attachmentNames = normalizedNames,
                                    e2eEnabled = request.e2eEnabled,
                                )

                            Timber.tag(TAG).d("Prepare request from: ${payload.senderName}")

                            val deferred = CompletableDeferred<Boolean>()
                            val shouldContinue =
                                synchronized(stateLock) {
                                    cleanupExpiredSessionsLocked()
                                    if (pendingApproval != null) {
                                        false
                                    } else {
                                        pendingApproval = PendingApproval(deferred)
                                        true
                                    }
                                }
                            if (!shouldContinue) {
                                call.respond(
                                    HttpStatusCode.TooManyRequests,
                                    json.encodeToString(
                                        PrepareResponse.serializer(),
                                        PrepareResponse(accepted = false, sessionToken = null),
                                    ),
                                )
                                return@post
                            }

                            onIncomingPrepare?.invoke(payload)

                            val accepted =
                                try {
                                    withTimeout(APPROVAL_TIMEOUT_MS) {
                                        deferred.await()
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    Timber.tag(TAG).w("Approval timed out")
                                    false
                                }
                            val sessionToken =
                                if (accepted) {
                                    val token = Uuid.random().toString()
                                    synchronized(stateLock) {
                                        approvedSessions[token] =
                                            ApprovedSession(
                                                requestHash = requestHash,
                                                attachmentNames = normalizedNames.toSet(),
                                                e2eEnabled = request.e2eEnabled,
                                                createdAtMs = System.currentTimeMillis(),
                                            )
                                    }
                                    token
                                } else {
                                    null
                                }

                            call.respondText(
                                json.encodeToString(
                                    PrepareResponse.serializer(),
                                    PrepareResponse(accepted = accepted, sessionToken = sessionToken),
                                ),
                                ContentType.Application.Json,
                            )
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error handling prepare request")
                            call.respond(HttpStatusCode.InternalServerError, "Error")
                        } finally {
                            synchronized(stateLock) {
                                pendingApproval = null
                            }
                        }
                    }

                    // Phase 2: Transfer - sender uploads memo + attachments
                    post("/share/transfer") {
                        transferHandler.handle(call)
                    }
                }
            }.start(wait = false)

        // Get the actual bound port
        // CIO binds quickly; poll a few times to be safe
        var resolvedPort: Int? = null
        repeat(5) {
            resolvedPort =
                server
                    ?.engine
                    ?.resolvedConnectors()
                    ?.firstOrNull()
                    ?.port
            if (resolvedPort != null && resolvedPort != 0) return@repeat
            kotlinx.coroutines.delay(100)
        }

        val finalPort = resolvedPort ?: port
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
        server?.stop(500, 1000)
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
}
