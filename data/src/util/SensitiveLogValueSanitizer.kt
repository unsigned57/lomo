package com.lomo.data.util

import java.security.MessageDigest

internal fun sanitizePathForLog(value: String?): String {
    if (value.isNullOrBlank()) {
        return "<empty>"
    }
    val normalized = value.trim()
    val leafName = normalized.substringAfterLast('/')
    val visiblePrefix = leafName.take(LOG_VISIBLE_PREFIX_LENGTH)
    val hash = normalized.sha256Hex().take(LOG_HASH_PREFIX_LENGTH)
    return buildString(visiblePrefix.length + hash.length + LOG_MASK_SEPARATOR_LENGTH) {
        append(visiblePrefix)
        if (leafName.length > visiblePrefix.length) {
            append("...")
        }
        append('#')
        append(hash)
    }
}

private fun String.sha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private const val LOG_VISIBLE_PREFIX_LENGTH = 4
private const val LOG_HASH_PREFIX_LENGTH = 8
private const val LOG_MASK_SEPARATOR_LENGTH = 4
