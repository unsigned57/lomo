package com.lomo.data.engine

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Binds opaque process capabilities to a stable workspace identity and an execution root.
 *
 * Grants are either a SAF tree or a Direct filesystem root. URI text and absolute file paths never
 * cross the FFI boundary: Rust receives the stable ID and the token. Revocation removes the whole
 * binding so a later resolve is indistinguishable from an unknown token.
 */
internal class CapabilityRegistry {
    private val grants = ConcurrentHashMap<String, RootCapabilityGrant>()

    fun register(
        token: String,
        treeUri: String,
    ): SafCapabilityGrant {
        val grant = SafCapabilityGrant.bind(token = token, treeUri = treeUri)
        return putGrant(grant) as SafCapabilityGrant
    }

    fun registerDirect(
        token: String,
        rootPath: File,
    ): DirectCapabilityGrant {
        val grant = DirectCapabilityGrant.bind(token = token, rootPath = rootPath)
        return putGrant(grant) as DirectCapabilityGrant
    }

    fun revoke(token: String) {
        grants.remove(token)
    }

    fun resolve(token: String): RootCapabilityGrant =
        grants[token]
            ?: throw CapabilityRegistryException(
                category = "permission",
                code = "unknown_capability_token",
                diagnostic = "Capability token is unknown or revoked",
            )

    private fun putGrant(grant: RootCapabilityGrant): RootCapabilityGrant {
        val existing = grants.putIfAbsent(grant.capabilityToken, grant)
        require(existing == null || existing.hasSameBindingAs(grant)) {
            "capability token is already bound to a different root"
        }
        return existing ?: grant
    }
}

internal sealed interface RootCapabilityGrant {
    val capabilityToken: String
    val stableWorkspaceId: StableWorkspaceId

    fun hasSameBindingAs(other: RootCapabilityGrant): Boolean
}

@JvmInline
internal value class StableWorkspaceId(
    val value: String,
) {
    init {
        require(
            value.length == PREFIX.length + SHA256_HEX_LENGTH &&
                value.startsWith(PREFIX) &&
                value.drop(PREFIX.length).all(::isLowerHex),
        ) {
            "stable workspace ID must be '$PREFIX' followed by a lowercase SHA-256 digest"
        }
    }

    private companion object {
        const val PREFIX = "ws-"
        const val SHA256_HEX_LENGTH = 64
    }
}

internal class SafCapabilityGrant private constructor(
    override val capabilityToken: String,
    override val stableWorkspaceId: StableWorkspaceId,
    val treeUri: String,
) : RootCapabilityGrant {
    override fun hasSameBindingAs(other: RootCapabilityGrant): Boolean =
        other is SafCapabilityGrant &&
            capabilityToken == other.capabilityToken &&
            stableWorkspaceId == other.stableWorkspaceId &&
            treeUri == other.treeUri

    companion object {
        fun bind(
            token: String,
            treeUri: String,
        ): SafCapabilityGrant {
            require(token.isNotBlank()) { "capability token must be non-blank" }
            return SafCapabilityGrant(
                capabilityToken = token,
                stableWorkspaceId = SafWorkspaceIdentity.fromTreeUri(treeUri),
                treeUri = treeUri,
            )
        }
    }
}

/** Canonical identity edge for persisted Android SAF tree selections. */
internal object SafWorkspaceIdentity {
    fun fromTreeUri(treeUri: String): StableWorkspaceId {
        require(treeUri.isNotBlank()) { "capability tree URI must be non-blank" }
        val uri = URI.create(treeUri)
        require(uri.scheme.equals("content", ignoreCase = true)) {
            "SAF tree URI must use the content scheme"
        }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "SAF tree URI must not contain a query or fragment"
        }
        val authority =
            requireNotNull(uri.rawAuthority?.takeIf { it.matches(SAF_AUTHORITY) }) {
                "SAF tree URI must contain a canonical provider authority"
            }.lowercase(Locale.ROOT)
        val rawPath = requireNotNull(uri.rawPath) { "SAF tree URI must contain a tree path" }
        require(rawPath.startsWith(TREE_PATH_PREFIX)) { "SAF tree URI must identify a tree root" }
        val rawDocumentId = rawPath.removePrefix(TREE_PATH_PREFIX)
        require(rawDocumentId.isNotBlank()) {
            "SAF tree URI must contain a non-blank tree document ID"
        }
        val treeRootSegment =
            if (rawDocumentId.contains("/document/")) {
                rawDocumentId.substringBefore("/document/")
            } else {
                rawDocumentId
            }
        val documentId =
            URLDecoder.decode(
                treeRootSegment.replace("+", "%2B"),
                StandardCharsets.UTF_8.name(),
            )
        require(documentId.isNotBlank()) { "SAF tree document ID must be non-blank" }
        val identityMaterial = "saf\u0000$authority\u0000$documentId"
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(identityMaterial.toByteArray(StandardCharsets.UTF_8))
        return StableWorkspaceId("ws-${digest.toLowerHex()}")
    }

    private val SAF_AUTHORITY = Regex("[A-Za-z0-9._-]+")
    private const val TREE_PATH_PREFIX = "/tree/"
}

internal class DirectCapabilityGrant private constructor(
    override val capabilityToken: String,
    override val stableWorkspaceId: StableWorkspaceId,
    val canonicalRoot: File,
) : RootCapabilityGrant {
    override fun hasSameBindingAs(other: RootCapabilityGrant): Boolean =
        other is DirectCapabilityGrant &&
            capabilityToken == other.capabilityToken &&
            stableWorkspaceId == other.stableWorkspaceId &&
            canonicalRoot == other.canonicalRoot

    companion object {
        fun bind(
            token: String,
            rootPath: File,
        ): DirectCapabilityGrant {
            require(token.isNotBlank()) { "capability token must be non-blank" }
            val canonical =
                try {
                    rootPath.canonicalFile
                } catch (error: java.io.IOException) {
                    throw CapabilityRegistryException(
                        category = "storage",
                        code = "workspace_root_unavailable",
                        diagnostic = "direct workspace root cannot be canonicalized: ${error.message}",
                        cause = error,
                    )
                }
            if (!canonical.isDirectory) {
                throw CapabilityRegistryException(
                    category = "validation",
                    code = "workspace_root_not_directory",
                    diagnostic = "direct workspace root must be a directory",
                )
            }
            return DirectCapabilityGrant(
                capabilityToken = token,
                stableWorkspaceId = DirectWorkspaceIdentity.fromCanonicalRoot(canonical),
                canonicalRoot = canonical,
            )
        }
    }
}

/** Canonical identity edge for Direct filesystem roots. Matches Rust `workspace_identity(direct)`. */
internal object DirectWorkspaceIdentity {
    fun fromCanonicalRoot(canonicalRoot: File): StableWorkspaceId {
        val identityMaterial = "direct\u0000${canonicalRoot.path}"
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(identityMaterial.toByteArray(StandardCharsets.UTF_8))
        return StableWorkspaceId("ws-${digest.toLowerHex()}")
    }
}

private fun isLowerHex(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f'

private fun ByteArray.toLowerHex(): String =
    buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and BYTE_MASK
            append(HEX[value ushr NIBBLE_BITS])
            append(HEX[value and NIBBLE_MASK])
        }
    }

private const val HEX = "0123456789abcdef"
private const val BYTE_MASK = 0xff
private const val NIBBLE_MASK = 0x0f
private const val NIBBLE_BITS = 4

internal class CapabilityRegistryException(
    val category: String,
    val code: String,
    val diagnostic: String,
    cause: Throwable? = null,
) : RuntimeException("$code: $diagnostic", cause)
