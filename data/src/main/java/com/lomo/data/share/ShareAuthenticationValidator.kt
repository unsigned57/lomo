package com.lomo.data.share

import com.lomo.data.share.LomoShareServer.PrepareRequest
import com.lomo.data.share.LomoShareServer.TransferMetadata
import io.ktor.http.HttpStatusCode

internal class ShareAuthenticationValidator {
    private val nonceLock = Any()
    private val usedAuthNonces = mutableMapOf<String, Long>()

    fun clearNonces() {
        synchronized(nonceLock) {
            usedAuthNonces.clear()
        }
    }

    suspend fun validatePrepareAuthentication(
        request: PrepareRequest,
        resolvePairingKeyHex: suspend () -> String?,
    ): ShareAuthValidation =
        validateAuthentication(
            e2eEnabled = request.e2eEnabled,
            authTimestampMs = request.authTimestampMs,
            authNonce = request.authNonce,
            providedSignatureHex = request.authSignature,
            resolvePairingKeyHex = resolvePairingKeyHex,
            payloadBuilder = {
                ShareAuthUtils.buildPreparePayloadToSign(
                    senderName = request.senderName,
                    encryptedContent = request.encryptedContent,
                    contentNonce = request.contentNonce,
                    timestamp = request.timestamp,
                    attachmentNames = request.attachments.map { it.name.trim() },
                    authTimestampMs = request.authTimestampMs,
                    authNonce = request.authNonce,
                )
            },
        )

    suspend fun validateTransferAuthentication(
        metadata: TransferMetadata,
        resolvePairingKeyHex: suspend () -> String?,
    ): ShareAuthValidation =
        validateAuthentication(
            e2eEnabled = metadata.e2eEnabled,
            authTimestampMs = metadata.authTimestampMs,
            authNonce = metadata.authNonce,
            providedSignatureHex = metadata.authSignature,
            resolvePairingKeyHex = resolvePairingKeyHex,
            payloadBuilder = {
                ShareAuthUtils.buildTransferPayloadToSign(
                    sessionToken = metadata.sessionToken,
                    encryptedContent = metadata.encryptedContent,
                    contentNonce = metadata.contentNonce,
                    timestamp = metadata.timestamp,
                    attachmentNames = metadata.attachmentNames.map { it.trim() },
                    authTimestampMs = metadata.authTimestampMs,
                    authNonce = metadata.authNonce,
                )
            },
        )

    private suspend fun validateAuthentication(
        e2eEnabled: Boolean,
        authTimestampMs: Long,
        authNonce: String,
        providedSignatureHex: String,
        resolvePairingKeyHex: suspend () -> String?,
        payloadBuilder: () -> String,
    ): ShareAuthValidation {
        val keyCandidates = resolveKeyCandidates(e2eEnabled, resolvePairingKeyHex)
        val validationError =
            when {
                !e2eEnabled -> ShareAuthValidation(ok = true, keyHex = null)
                keyCandidates.isEmpty() -> pairingNotConfiguredValidation()
                !ShareAuthUtils.isTimestampWithinWindow(authTimestampMs) -> {
                    ShareAuthValidation(false, HttpStatusCode.Unauthorized, EXPIRED_AUTH_TIMESTAMP_MESSAGE)
                }

                !registerNonce(authNonce) -> {
                    ShareAuthValidation(false, HttpStatusCode.Forbidden, REPLAY_DETECTED_MESSAGE)
                }

                else -> null
            }
        val verifiedKey =
            if (validationError == null) {
                val payload = payloadBuilder()
                keyCandidates.firstOrNull { candidate ->
                    ShareAuthUtils.verifySignature(
                        keyHex = candidate,
                        payload = payload,
                        providedSignatureHex = providedSignatureHex,
                    )
                }
            } else {
                null
            }
        return validationError
            ?: verifiedKey?.let { ShareAuthValidation(true, keyHex = it) }
            ?: ShareAuthValidation(false, HttpStatusCode.Unauthorized, INVALID_AUTH_SIGNATURE_MESSAGE)
    }

    private suspend fun resolveKeyCandidates(
        e2eEnabled: Boolean,
        resolvePairingKeyHex: suspend () -> String?,
    ): List<String> =
        if (!e2eEnabled) {
            emptyList()
        } else {
            resolvePairingKeyHex()
                ?.trim()
                ?.takeIf(::isValidKeyHex)
                ?.let(::resolveCandidateKeyHexes)
                .orEmpty()
        }

    private fun pairingNotConfiguredValidation(): ShareAuthValidation =
        ShareAuthValidation(
            ok = false,
            status = HttpStatusCode.PreconditionFailed,
            message = PAIRING_NOT_CONFIGURED_MESSAGE,
        )

    private fun cleanupExpiredAuthNoncesLocked(nowMs: Long = System.currentTimeMillis()) {
        usedAuthNonces.entries.removeIf { (_, issuedAt) ->
            nowMs - issuedAt > AUTH_NONCE_TTL_MS
        }
    }

    private fun registerNonce(nonce: String): Boolean =
        synchronized(nonceLock) {
            val now = System.currentTimeMillis()
            cleanupExpiredAuthNoncesLocked(now)
            if (usedAuthNonces.containsKey(nonce)) {
                false
            } else {
                if (usedAuthNonces.size >= MAX_AUTH_NONCES_TRACKED) {
                    val oldestKey = usedAuthNonces.minByOrNull { it.value }?.key
                    if (oldestKey != null) {
                        usedAuthNonces.remove(oldestKey)
                    }
                }
                usedAuthNonces[nonce] = now
                true
            }
        }

    private companion object {
        private const val AUTH_NONCE_TTL_MS = 10 * 60 * 1000L
        private const val EXPIRED_AUTH_TIMESTAMP_MESSAGE = "Expired auth timestamp"
        private const val INVALID_AUTH_SIGNATURE_MESSAGE = "Invalid auth signature"
        private const val MAX_AUTH_NONCES_TRACKED = 5000
        private const val PAIRING_NOT_CONFIGURED_MESSAGE =
            "LAN share pairing code is not configured on receiver"
        private const val REPLAY_DETECTED_MESSAGE = "Replay detected"
    }
}
