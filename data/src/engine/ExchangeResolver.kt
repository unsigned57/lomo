package com.lomo.data.engine

import com.lomo.nativebridge.ExchangeArtifact
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class ExchangeArtifactReference(
    val token: String,
    val length: ULong,
    val digest: String,
)

/**
 * Resolves opaque exchange tokens onto application-private files under [exchangeRoot].
 *
 * Tokens are not workspace paths. Absolute segments, parent traversal, blanks, and backslashes are
 * rejected before any I/O so SAF content can stream into a bounded private file only.
 */
internal class ExchangeResolver(
    exchangeRoot: File,
) {
    private val root: File = exchangeRoot.canonicalFile

    init {
        root.mkdirs()
        require(root.isDirectory) { "exchange root is not a directory: $root" }
    }

    fun resolveFile(token: String): File {
        validateToken(token)
        val candidate = File(root, token).canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator) && candidate != root) {
            throw ExchangeResolverException(
                category = "validation",
                code = "invalid_exchange_token",
                diagnostic = "Exchange token escaped the exchange root",
            )
        }
        return candidate
    }

    fun digestArtifact(token: String): ExchangeArtifact {
        val file = resolveFile(token)
        require(file.isFile) { "exchange artifact is not a file: $file" }
        val length = file.length().toULong()
        val digest = file.inputStream().use { input -> input.sha256Hex() }
        return ExchangeArtifact(token = token, length = length, digest = digest)
    }

    fun readUtf8Artifact(reference: ExchangeArtifactReference): String {
        validateReference(reference)
        val file = resolveFile(reference.token)
        if (!file.isFile) {
            throw ExchangeResolverException(
                category = "storage",
                code = "exchange_artifact_missing",
                diagnostic = "Exchange artifact does not exist",
            )
        }
        val actualLength = file.length().toULong()
        if (actualLength != reference.length) {
            throw artifactMismatch("Exchange artifact length does not match its typed reference")
        }
        val bytes = file.readBytes()
        val actualDigest = bytes.sha256Hex()
        if (actualDigest != reference.digest) {
            throw artifactMismatch("Exchange artifact digest does not match its typed reference")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_error: CharacterCodingException) {
            throw ExchangeResolverException(
                category = "corruption",
                code = "exchange_artifact_invalid_utf8",
                diagnostic = "Exchange artifact is not valid UTF-8",
            )
        }
    }

    /** Reads and consumes a one-shot scan body after its digest/UTF-8 contract is verified. */
    fun consumeUtf8Artifact(reference: ExchangeArtifactReference): String {
        val content = readUtf8Artifact(reference)
        val file = resolveFile(reference.token)
        if (!file.delete() && file.exists()) {
            throw ExchangeResolverException(
                category = "storage",
                code = "exchange_artifact_cleanup_failed",
                diagnostic = "Exchange artifact could not be removed after consumption",
            )
        }
        return content
    }

    fun writeStreaming(
        token: String,
        source: InputStream,
    ): ExchangeArtifact {
        val file = resolveFile(token)
        file.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0uL
        file.outputStream().use { output ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                length += read.toULong()
            }
        }
        return ExchangeArtifact(
            token = token,
            length = length,
            digest = digest.finishSha256Hex(),
        )
    }

    private fun validateToken(token: String) {
        if (!isValidExchangeToken(token)) {
            throw ExchangeResolverException(
                category = "validation",
                code = "invalid_exchange_token",
                diagnostic = "Exchange token must be a bounded opaque identifier without path segments",
            )
        }
    }

    private fun validateReference(reference: ExchangeArtifactReference) {
        validateToken(reference.token)
        if (reference.length > MAX_MEMO_CONTENT_UTF8_BYTES.toULong()) {
            throw ExchangeResolverException(
                category = "resource_limit",
                code = "exchange_artifact_too_large",
                diagnostic = "Exchange memo artifact exceeds the bounded UTF-8 size",
            )
        }
        if (reference.digest.length != SHA256_HEX_LENGTH || reference.digest.any { it !in SHA256_HEX_CHARS }) {
            throw ExchangeResolverException(
                category = "validation",
                code = "invalid_exchange_artifact_reference",
                diagnostic = "Exchange artifact digest must be lowercase SHA-256",
            )
        }
    }

    private fun artifactMismatch(diagnostic: String): ExchangeResolverException =
        ExchangeResolverException(
            category = "corruption",
            code = "exchange_artifact_mismatch",
            diagnostic = diagnostic,
        )

    private fun isValidExchangeToken(token: String): Boolean {
        if (token.isEmpty() || token.length > MAX_TOKEN_BYTES) return false
        if (token.startsWith('/') || token.contains('\\') || token.contains("..")) return false
        if (token.any { it.isISOControl() }) return false
        return token.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }
    }

    private companion object {
        const val MAX_TOKEN_BYTES = 128
        const val MAX_MEMO_CONTENT_UTF8_BYTES = 400_000
        const val SHA256_HEX_LENGTH = 64
        const val STREAM_BUFFER_SIZE = 16 * 1024
        val SHA256_HEX_CHARS = '0'..'9' union 'a'..'f'
    }
}

internal class ExchangeResolverException(
    val category: String,
    val code: String,
    val diagnostic: String,
) : RuntimeException("$code: $diagnostic")

internal fun InputStream.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
    }
    return digest.finishSha256Hex()
}

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHexLower()

/**
 * Finishes a streaming hash and encodes it.
 *
 * [sha256Hex] *computes* a hash over content, so at a call site already holding a [MessageDigest]
 * the reading of `digest().sha256Hex()` is plausible and wrong: it hashes the digest a second time.
 * `ByteArray` cannot distinguish content from a digest of content, so the confusion is removed by
 * never handing the raw digest bytes to a call site.
 */
internal fun MessageDigest.finishSha256Hex(): String = digest().toHexLower()

private val HEX_CHARS = "0123456789abcdef".toCharArray()
private const val BYTE_MASK = 0xFF
private const val NIBBLE_SHIFT = 4
private const val NIBBLE_MASK = 0x0F

private fun ByteArray.toHexLower(): String {
    val result = CharArray(size * 2)
    for (i in indices) {
        val byte = this[i].toInt() and BYTE_MASK
        result[i * 2] = HEX_CHARS[byte ushr NIBBLE_SHIFT]
        result[i * 2 + 1] = HEX_CHARS[byte and NIBBLE_MASK]
    }
    return String(result)
}
