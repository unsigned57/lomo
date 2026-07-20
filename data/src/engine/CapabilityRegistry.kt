package com.lomo.data.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps opaque capability tokens to persisted SAF tree URI strings.
 *
 * URI text never crosses the FFI boundary; Rust only sees tokens. Revocation removes the mapping so
 * a later resolve is indistinguishable from an unknown token. Conversion to Android [android.net.Uri]
 * happens only at the documents gateway edge.
 */
internal class CapabilityRegistry {
    private val grants = ConcurrentHashMap<String, String>()

    fun register(
        token: String,
        treeUri: String,
    ) {
        require(token.isNotBlank()) { "capability token must be non-blank" }
        require(treeUri.isNotBlank()) { "capability tree URI must be non-blank" }
        grants[token] = treeUri
    }

    fun revoke(token: String) {
        grants.remove(token)
    }

    fun resolve(token: String): String =
        grants[token]
            ?: throw CapabilityRegistryException(
                category = "permission",
                code = "unknown_capability_token",
                diagnostic = "Capability token is unknown or revoked",
            )
}

internal class CapabilityRegistryException(
    val category: String,
    val code: String,
    val diagnostic: String,
) : RuntimeException("$code: $diagnostic")
