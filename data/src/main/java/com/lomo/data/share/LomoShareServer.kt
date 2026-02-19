package com.lomo.data.share

import com.lomo.domain.model.ShareAttachmentInfo
import com.lomo.domain.model.SharePayload
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Ktor-based embedded HTTP server that handles incoming share requests from LAN peers.
 */
class LomoShareServer {
    companion object {
        private const val TAG = "LomoShareServer"
        private const val APPROVAL_TIMEOUT_MS = 60_000L // 60 seconds to approve
        private const val SESSION_TTL_MS = 120_000L
        private const val MAX_PREPARE_BODY_CHARS = 64 * 1024
        private const val MAX_TRANSFER_BODY_BYTES = 120L * 1024L * 1024L
        private const val MAX_SENDER_NAME_CHARS = 64
        private const val MAX_MEMO_CHARS = 200_000
        private const val MAX_ENCRYPTED_MEMO_CHARS = 600_000
        private const val MAX_ATTACHMENTS = 20
        private const val MAX_ATTACHMENT_NAME_CHARS = 1024
        private const val MAX_ATTACHMENT_SIZE_BYTES = 100L * 1024L * 1024L
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        private const val GCM_TAG_BYTES = 16L
        private const val MAX_ATTACHMENT_ENCRYPTED_SIZE_BYTES = MAX_ATTACHMENT_SIZE_BYTES + GCM_TAG_BYTES
        private const val MAX_TOTAL_ATTACHMENT_ENCRYPTED_BYTES =
            MAX_TOTAL_ATTACHMENT_BYTES + (MAX_ATTACHMENTS * GCM_TAG_BYTES)
        private const val MAX_STORAGE_FILENAME_CHARS = 96
        private const val AUTH_NONCE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_AUTH_NONCE_CHARS = 64
        private const val MAX_AUTH_NONCES_TRACKED = 5000
        private const val MAX_NONCE_BASE64_CHARS = 64
    }

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val stateLock = Any()

    private data class PendingApproval(
        val deferred: CompletableDeferred<Boolean>,
    )

    private data class ApprovedSession(
        val requestHash: String,
        val attachmentNames: Set<String>,
        val createdAtMs: Long,
    )

    private var pendingApproval: PendingApproval? = null
    private val approvedSessions = mutableMapOf<String, ApprovedSession>()
    private val usedAuthNonces = mutableMapOf<String, Long>()

    // Callback to notify UI of incoming share request
    var onIncomingPrepare: ((SharePayload) -> Unit)? = null

    // Callback to save attachments; returns the saved file path
    var onSaveAttachment: (suspend (name: String, type: String, bytes: ByteArray) -> String?)? = null

    // Callback to save the received memo
    var onSaveMemo: (suspend (content: String, timestamp: Long, attachmentMappings: Map<String, String>) -> Unit)? = null

    // Callback to resolve local pairing key for request authentication
    var getPairingKeyHex: (suspend () -> String?)? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun start(port: Int = 0): Int {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
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
                        if (contentLength != null && contentLength > MAX_PREPARE_BODY_CHARS) {
                            call.respond(HttpStatusCode.PayloadTooLarge, "Prepare payload too large")
                            return@post
                        }

                        val body = call.receiveText()
                        if (body.length > MAX_PREPARE_BODY_CHARS) {
                            call.respond(HttpStatusCode.PayloadTooLarge, "Prepare payload too large")
                            return@post
                        }
                        val request = json.decodeFromString<PrepareRequest>(body)
                        val validationError = validatePrepareRequest(request)
                        if (validationError != null) {
                            call.respond(HttpStatusCode.BadRequest, validationError)
                            return@post
                        }
                        val authValidation = validatePrepareAuthentication(request)
                        if (!authValidation.ok) {
                            call.respond(authValidation.status, authValidation.message)
                            return@post
                        }
                        val keyHex = authValidation.keyHex
                        if (keyHex.isNullOrBlank()) {
                            call.respond(HttpStatusCode.PreconditionFailed, "Missing pairing key")
                            return@post
                        }
                        val decryptedContent =
                            ShareCryptoUtils.decryptText(
                                keyHex = keyHex,
                                ciphertextBase64 = request.encryptedContent,
                                nonceBase64 = request.contentNonce,
                                aad = "memo-content",
                            )
                        if (decryptedContent == null) {
                            call.respond(HttpStatusCode.Unauthorized, "Cannot decrypt prepare content")
                            return@post
                        }
                        if (decryptedContent.length > MAX_MEMO_CHARS) {
                            call.respond(HttpStatusCode.PayloadTooLarge, "Memo content too large")
                            return@post
                        }

                        val payload = request.toSharePayload(decryptedContent)
                        val normalizedNames = request.attachments.map { it.name.trim() }
                        val requestHash = buildRequestHash(decryptedContent, request.timestamp, normalizedNames)

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

                        val accepted = try {
                            withTimeout(APPROVAL_TIMEOUT_MS) {
                                deferred.await()
                            }
                        } catch (e: TimeoutCancellationException) {
                            Timber.tag(TAG).w("Approval timed out")
                            false
                        }
                        val sessionToken =
                            if (accepted) {
                                val token = UUID.randomUUID().toString()
                                synchronized(stateLock) {
                                    approvedSessions[token] =
                                        ApprovedSession(
                                            requestHash = requestHash,
                                            attachmentNames = normalizedNames.toSet(),
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
                    try {
                        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        if (contentLength != null && contentLength > MAX_TRANSFER_BODY_BYTES) {
                            call.respond(HttpStatusCode.PayloadTooLarge, "Transfer payload too large")
                            return@post
                        }

                        val multipart = call.receiveMultipart()
                        var metadata: TransferMetadata? = null
                        var transferKeyHex: String? = null
                        var decryptedContent: String? = null
                        var expectedAttachmentNames = emptySet<String>()
                        var expectedAttachmentNonces = emptyMap<String, String>()
                        var expectedByUploadName = emptyMap<String, String>()
                        val attachmentMappings = mutableMapOf<String, String>() // original -> new path
                        val receivedAttachmentNames = mutableSetOf<String>()
                        var totalAttachmentBytes = 0L

                        var part = multipart.readPart()
                        while (part != null) {
                            when (part) {
                                is io.ktor.http.content.PartData.FormItem -> {
                                    if (part.name == "metadata") {
                                        if (metadata != null) {
                                            part.dispose()
                                            call.respond(HttpStatusCode.BadRequest, "Duplicate metadata")
                                            return@post
                                        }
                                        val meta = json.decodeFromString<TransferMetadata>(part.value)
                                        metadata = meta
                                        val metaError = validateTransferMetadata(meta)
                                        if (metaError != null) {
                                            part.dispose()
                                            call.respond(HttpStatusCode.BadRequest, metaError)
                                            return@post
                                        }
                                        val authValidation = validateTransferAuthentication(meta)
                                        if (!authValidation.ok) {
                                            part.dispose()
                                            call.respond(authValidation.status, authValidation.message)
                                            return@post
                                        }
                                        val keyHex = authValidation.keyHex
                                        if (keyHex.isNullOrBlank()) {
                                            part.dispose()
                                            call.respond(HttpStatusCode.PreconditionFailed, "Missing pairing key")
                                            return@post
                                        }
                                        val plainContent =
                                            ShareCryptoUtils.decryptText(
                                                keyHex = keyHex,
                                                ciphertextBase64 = meta.encryptedContent,
                                                nonceBase64 = meta.contentNonce,
                                                aad = "memo-content",
                                            )
                                        if (plainContent == null) {
                                            part.dispose()
                                            call.respond(HttpStatusCode.Unauthorized, "Cannot decrypt transfer content")
                                            return@post
                                        }
                                        if (plainContent.length > MAX_MEMO_CHARS) {
                                            part.dispose()
                                            call.respond(HttpStatusCode.PayloadTooLarge, "Memo content too large")
                                            return@post
                                        }

                                        val normalizedNames = meta.attachmentNames.map { it.trim() }
                                        val requestHash = buildRequestHash(plainContent, meta.timestamp, normalizedNames)
                                        val validSession =
                                            synchronized(stateLock) {
                                                cleanupExpiredSessionsLocked()
                                                val existing = approvedSessions[meta.sessionToken]
                                                if (existing == null ||
                                                    existing.requestHash != requestHash ||
                                                    existing.attachmentNames != normalizedNames.toSet()
                                                ) {
                                                    false
                                                } else {
                                                    approvedSessions.remove(meta.sessionToken)
                                                    true
                                                }
                                            }
                                        if (!validSession) {
                                            part.dispose()
                                            call.respond(HttpStatusCode.Forbidden, "Invalid or expired share session")
                                            return@post
                                        }
                                        expectedAttachmentNames = normalizedNames.toSet()
                                        expectedAttachmentNonces = meta.attachmentNonces.mapKeys { it.key.trim() }
                                        expectedByUploadName = buildUploadNameLookup(expectedAttachmentNames)
                                        transferKeyHex = keyHex
                                        decryptedContent = plainContent
                                    }
                                }

                                is io.ktor.http.content.PartData.FileItem -> {
                                    if (metadata == null) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.BadRequest, "Metadata must be sent before attachments")
                                        return@post
                                    }

                                    val uploadName = part.originalFileName?.trim()
                                    val expectedReferenceName =
                                        when {
                                            uploadName.isNullOrEmpty() -> null
                                            uploadName in expectedAttachmentNames -> uploadName
                                            else -> expectedByUploadName[baseAttachmentName(uploadName)]
                                        }
                                    if (expectedReferenceName == null) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.BadRequest, "Unexpected attachment")
                                        return@post
                                    }
                                    if (!receivedAttachmentNames.add(expectedReferenceName)) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.BadRequest, "Duplicate attachment")
                                        return@post
                                    }

                                    val type = when {
                                        part.contentType?.match(ContentType.Image.Any) == true -> "image"
                                        part.contentType?.match(ContentType.Audio.Any) == true -> "audio"
                                        else -> "unknown"
                                    }
                                    if (type == "unknown") {
                                        part.dispose()
                                        call.respond(HttpStatusCode.UnsupportedMediaType, "Unsupported attachment type")
                                        return@post
                                    }

                                    val bytes = readBytesLimited(part, MAX_ATTACHMENT_ENCRYPTED_SIZE_BYTES)
                                    totalAttachmentBytes += bytes.size.toLong()
                                    if (totalAttachmentBytes > MAX_TOTAL_ATTACHMENT_ENCRYPTED_BYTES) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.PayloadTooLarge, "Attachments too large")
                                        return@post
                                    }

                                    val attachmentNonce = expectedAttachmentNonces[expectedReferenceName]
                                    if (attachmentNonce.isNullOrBlank()) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.BadRequest, "Missing attachment nonce")
                                        return@post
                                    }
                                    val keyHex = transferKeyHex
                                    if (keyHex.isNullOrBlank()) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.PreconditionFailed, "Missing transfer key")
                                        return@post
                                    }
                                    val decryptedBytes =
                                        ShareCryptoUtils.decryptBytes(
                                            keyHex = keyHex,
                                            ciphertext = bytes,
                                            nonceBase64 = attachmentNonce,
                                            aad = "attachment:$expectedReferenceName",
                                        )
                                    if (decryptedBytes == null) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.Unauthorized, "Cannot decrypt attachment")
                                        return@post
                                    }
                                    if (decryptedBytes.size.toLong() > MAX_ATTACHMENT_SIZE_BYTES) {
                                        part.dispose()
                                        call.respond(HttpStatusCode.PayloadTooLarge, "Attachment too large")
                                        return@post
                                    }

                                    val safeStorageName = sanitizeStorageFilename(expectedReferenceName)
                                    Timber.tag(TAG).d("Received attachment: $expectedReferenceName ($type, ${decryptedBytes.size} bytes)")

                                    val savedPath = onSaveAttachment?.invoke(
                                        safeStorageName,
                                        type,
                                        decryptedBytes,
                                    )
                                    if (!savedPath.isNullOrBlank()) {
                                        attachmentMappings[expectedReferenceName] = savedPath
                                    } else {
                                        throw IllegalStateException("Failed to save attachment: $expectedReferenceName")
                                    }
                                }

                                else -> {}
                            }
                            part.dispose()
                            part = multipart.readPart()
                        }

                        val meta = metadata
                        if (meta == null) {
                            call.respond(HttpStatusCode.BadRequest, "Missing metadata")
                            return@post
                        }

                        if (expectedAttachmentNames != receivedAttachmentNames) {
                            call.respond(HttpStatusCode.BadRequest, "Attachment set mismatch")
                            return@post
                        }

                        if (onSaveMemo == null) {
                            throw IllegalStateException("Memo saver callback is not configured")
                        }
                        val contentToSave = decryptedContent
                        if (contentToSave == null) {
                            call.respond(HttpStatusCode.BadRequest, "Missing decrypted content")
                            return@post
                        }
                        onSaveMemo?.invoke(contentToSave, meta.timestamp, attachmentMappings)
                        Timber.tag(TAG).d("Memo saved with ${attachmentMappings.size} attachments")

                        call.respondText(
                            json.encodeToString(TransferResponse.serializer(), TransferResponse(true)),
                            ContentType.Application.Json,
                        )
                    } catch (e: IllegalArgumentException) {
                        Timber.tag(TAG).w(e, "Rejected transfer payload")
                        call.respond(HttpStatusCode.PayloadTooLarge, e.message ?: "Transfer payload too large")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling transfer request")
                        call.respond(HttpStatusCode.InternalServerError, "Transfer error")
                    }
                }
            }
        }.start(wait = false)

        // Get the actual bound port
        // CIO binds quickly; poll a few times to be safe
        var resolvedPort: Int? = null
        repeat(5) {
            resolvedPort = server?.engine?.resolvedConnectors()?.firstOrNull()?.port
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
            usedAuthNonces.clear()
        }
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

    private fun validatePrepareRequest(request: PrepareRequest): String? {
        if (request.senderName.isBlank() || request.senderName.length > MAX_SENDER_NAME_CHARS) {
            return "Invalid sender name"
        }
        if (request.encryptedContent.isBlank() || request.encryptedContent.length > MAX_ENCRYPTED_MEMO_CHARS) {
            return "Encrypted memo content too large"
        }
        if (!isValidContentNonce(request.contentNonce)) {
            return "Invalid content nonce"
        }
        if (request.attachments.size > MAX_ATTACHMENTS) {
            return "Too many attachments"
        }

        val seenNames = mutableSetOf<String>()
        for (attachment in request.attachments) {
            val name = attachment.name.trim()
            if (!isValidAttachmentReferenceName(name)) {
                return "Invalid attachment name"
            }
            if (!seenNames.add(name)) {
                return "Duplicate attachment name"
            }
            if (attachment.type !in setOf("image", "audio")) {
                return "Unsupported attachment type"
            }
            if (attachment.size < 0) {
                return "Invalid attachment size"
            }
            if (attachment.size > MAX_ATTACHMENT_SIZE_BYTES) {
                return "Attachment too large"
            }
        }

        if (request.authTimestampMs <= 0L) {
            return "Invalid auth timestamp"
        }
        if (!isValidAuthNonce(request.authNonce)) {
            return "Invalid auth nonce"
        }
        if (!isValidSignatureHex(request.authSignature)) {
            return "Invalid auth signature"
        }
        return null
    }

    private fun validateTransferMetadata(metadata: TransferMetadata): String? {
        if (metadata.sessionToken.isBlank()) {
            return "Missing share session token"
        }
        if (metadata.encryptedContent.isBlank() || metadata.encryptedContent.length > MAX_ENCRYPTED_MEMO_CHARS) {
            return "Encrypted memo content too large"
        }
        if (!isValidContentNonce(metadata.contentNonce)) {
            return "Invalid content nonce"
        }
        if (metadata.attachmentNames.size > MAX_ATTACHMENTS) {
            return "Too many attachments"
        }

        val seenNames = mutableSetOf<String>()
        val normalizedNames = metadata.attachmentNames.map { it.trim() }
        for (name in normalizedNames) {
            if (!isValidAttachmentReferenceName(name)) {
                return "Invalid attachment name"
            }
            if (!seenNames.add(name)) {
                return "Duplicate attachment name"
            }
        }
        if (metadata.attachmentNonces.size > MAX_ATTACHMENTS) {
            return "Too many attachment nonces"
        }
        val normalizedAttachmentNonces = mutableMapOf<String, String>()
        for ((rawName, nonce) in metadata.attachmentNonces) {
            val trimmedName = rawName.trim()
            if (!isValidAttachmentReferenceName(trimmedName)) {
                return "Invalid attachment nonce name"
            }
            if (normalizedAttachmentNonces.put(trimmedName, nonce) != null) {
                return "Duplicate attachment nonce name"
            }
        }
        if (normalizedAttachmentNonces.keys != seenNames) {
            return "Attachment nonce mismatch"
        }
        for ((_, nonce) in normalizedAttachmentNonces) {
            if (!isValidContentNonce(nonce)) {
                return "Invalid attachment nonce"
            }
        }
        if (metadata.authTimestampMs <= 0L) {
            return "Invalid auth timestamp"
        }
        if (!isValidAuthNonce(metadata.authNonce)) {
            return "Invalid auth nonce"
        }
        if (!isValidSignatureHex(metadata.authSignature)) {
            return "Invalid auth signature"
        }
        return null
    }

    private data class AuthValidation(
        val ok: Boolean,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val message: String = "",
        val keyHex: String? = null,
    )

    private suspend fun validatePrepareAuthentication(request: PrepareRequest): AuthValidation {
        val pairingKeyHex = getPairingKeyHex?.invoke()?.trim()
        if (!ShareAuthUtils.isValidKeyHex(pairingKeyHex)) {
            return AuthValidation(
                ok = false,
                status = HttpStatusCode.PreconditionFailed,
                message = "LAN share pairing code is not configured on receiver",
            )
        }
        val keyHex = pairingKeyHex ?: return AuthValidation(
            ok = false,
            status = HttpStatusCode.PreconditionFailed,
            message = "LAN share pairing code is not configured on receiver",
        )
        if (!ShareAuthUtils.isTimestampWithinWindow(request.authTimestampMs)) {
            return AuthValidation(false, HttpStatusCode.Unauthorized, "Expired auth timestamp")
        }
        if (!registerNonce(request.authNonce)) {
            return AuthValidation(false, HttpStatusCode.Forbidden, "Replay detected")
        }
        val payload =
            ShareAuthUtils.buildPreparePayloadToSign(
                senderName = request.senderName,
                encryptedContent = request.encryptedContent,
                contentNonce = request.contentNonce,
                timestamp = request.timestamp,
                attachmentNames = request.attachments.map { it.name.trim() },
                authTimestampMs = request.authTimestampMs,
                authNonce = request.authNonce,
            )
        val verified =
            ShareAuthUtils.verifySignature(
                keyHex = keyHex,
                payload = payload,
                providedSignatureHex = request.authSignature,
            )
        return if (verified) {
            AuthValidation(true, keyHex = keyHex)
        } else {
            AuthValidation(false, HttpStatusCode.Unauthorized, "Invalid auth signature")
        }
    }

    private suspend fun validateTransferAuthentication(metadata: TransferMetadata): AuthValidation {
        val pairingKeyHex = getPairingKeyHex?.invoke()?.trim()
        if (!ShareAuthUtils.isValidKeyHex(pairingKeyHex)) {
            return AuthValidation(
                ok = false,
                status = HttpStatusCode.PreconditionFailed,
                message = "LAN share pairing code is not configured on receiver",
            )
        }
        val keyHex = pairingKeyHex ?: return AuthValidation(
            ok = false,
            status = HttpStatusCode.PreconditionFailed,
            message = "LAN share pairing code is not configured on receiver",
        )
        if (!ShareAuthUtils.isTimestampWithinWindow(metadata.authTimestampMs)) {
            return AuthValidation(false, HttpStatusCode.Unauthorized, "Expired auth timestamp")
        }
        if (!registerNonce(metadata.authNonce)) {
            return AuthValidation(false, HttpStatusCode.Forbidden, "Replay detected")
        }
        val payload =
            ShareAuthUtils.buildTransferPayloadToSign(
                sessionToken = metadata.sessionToken,
                encryptedContent = metadata.encryptedContent,
                contentNonce = metadata.contentNonce,
                timestamp = metadata.timestamp,
                attachmentNames = metadata.attachmentNames.map { it.trim() },
                authTimestampMs = metadata.authTimestampMs,
                authNonce = metadata.authNonce,
            )
        val verified =
            ShareAuthUtils.verifySignature(
                keyHex = keyHex,
                payload = payload,
                providedSignatureHex = metadata.authSignature,
            )
        return if (verified) {
            AuthValidation(true, keyHex = keyHex)
        } else {
            AuthValidation(false, HttpStatusCode.Unauthorized, "Invalid auth signature")
        }
    }

    private fun isValidAttachmentReferenceName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_ATTACHMENT_NAME_CHARS) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun sanitizeStorageFilename(referenceName: String): String {
        val baseName =
            referenceName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
        val cleaned =
            baseName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(MAX_STORAGE_FILENAME_CHARS)
        return if (cleaned.isBlank()) {
            "attachment_${System.currentTimeMillis()}"
        } else {
            cleaned
        }
    }

    private fun baseAttachmentName(name: String): String {
        return name.substringAfterLast('/').substringAfterLast('\\')
    }

    private fun buildUploadNameLookup(expectedAttachmentNames: Set<String>): Map<String, String> {
        val grouped = expectedAttachmentNames.groupBy { baseAttachmentName(it) }
        return grouped.mapNotNull { (uploadName, refs) ->
            if (uploadName.isBlank() || refs.size != 1) {
                null
            } else {
                uploadName to refs.first()
            }
        }.toMap()
    }

    private fun cleanupExpiredSessionsLocked(nowMs: Long = System.currentTimeMillis()) {
        approvedSessions.entries.removeIf { (_, session) ->
            nowMs - session.createdAtMs > SESSION_TTL_MS
        }
    }

    private fun cleanupExpiredAuthNoncesLocked(nowMs: Long = System.currentTimeMillis()) {
        usedAuthNonces.entries.removeIf { (_, issuedAt) ->
            nowMs - issuedAt > AUTH_NONCE_TTL_MS
        }
    }

    private fun registerNonce(nonce: String): Boolean {
        return synchronized(stateLock) {
            val now = System.currentTimeMillis()
            cleanupExpiredAuthNoncesLocked(now)
            if (usedAuthNonces.containsKey(nonce)) {
                false
            } else {
                if (usedAuthNonces.size >= MAX_AUTH_NONCES_TRACKED) {
                    val oldestKey =
                        usedAuthNonces.minByOrNull { it.value }?.key
                    if (oldestKey != null) {
                        usedAuthNonces.remove(oldestKey)
                    }
                }
                usedAuthNonces[nonce] = now
                true
            }
        }
    }

    private fun isValidAuthNonce(nonce: String): Boolean {
        if (nonce.isBlank() || nonce.length > MAX_AUTH_NONCE_CHARS) return false
        return nonce.matches(Regex("^[0-9a-fA-F]+$"))
    }

    private fun isValidContentNonce(nonceBase64: String): Boolean {
        if (nonceBase64.isBlank() || nonceBase64.length > MAX_NONCE_BASE64_CHARS) return false
        return try {
            Base64.getDecoder().decode(nonceBase64).size == 12
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidSignatureHex(signature: String): Boolean {
        return signature.matches(Regex("^[0-9a-fA-F]{64}$"))
    }

    private fun buildRequestHash(
        content: String,
        timestamp: Long,
        attachmentNames: List<String>,
    ): String {
        val canonicalNames = attachmentNames.map { it.trim() }.sorted()
        val raw =
            buildString {
                append(timestamp)
                append('\n')
                append(content)
                append('\n')
                canonicalNames.forEach { append(it).append('\n') }
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readBytesLimited(
        part: io.ktor.http.content.PartData.FileItem,
        maxBytes: Long,
    ): ByteArray {
        part.provider().toInputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read.toLong()
                if (total > maxBytes) {
                    throw IllegalArgumentException("Attachment too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    // --- Protocol Data Classes ---

    @Serializable
    data class PrepareRequest(
        val senderName: String,
        val encryptedContent: String,
        val contentNonce: String,
        val timestamp: Long,
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
