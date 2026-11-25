package com.lomo.domain.model

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val CONTENT_URI_SCHEME = "content://"
private const val TREE_PATH_SEGMENT = "/tree/"
private const val DOCUMENT_PATH_SEGMENT = "/document/"
private const val ENCODED_AT_LENGTH = 3
private const val PERCENT_ESCAPE_LENGTH = 3
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4
private val AUTHORITY_TERMINATORS = charArrayOf('/', '?', '#')
private val NUMERIC_USER_ID_REGEX = Regex("[0-9]+")
private val PROVIDER_AUTHORITY_REGEX = Regex("[A-Za-z0-9._-]+")

/** Parsed SAF content URI authority separating an optional numeric multi-user prefix from the provider name. */
data class ContentUriAuthority(val userId: String?, val provider: String)

private data class ParsedContentAuthority(
    val userId: String?,
    val normalizedProvider: String,
    val rawProvider: String,
    val authorityEnd: Int,
)

/**
 * Parses the authority segment of a `content://` URI, separating an optional numeric user-ID
 * prefix (such as `0@` returned across Android cloned/dual-app user boundaries) from the
 * underlying provider authority. Rejects non-numeric userInfo, empty segments, ports, and multiple `@`.
 */
fun parseContentUriAuthority(uri: String): ContentUriAuthority? =
    parseRawAuthority(uri)?.let { ContentUriAuthority(userId = it.userId, provider = it.normalizedProvider) }

/**
 * Compares two SAF tree URIs by provider authority, optional cross-user prefix, and decoded tree
 * document ID. A bare persisted grant (`com.android.externalstorage.documents`, as returned by
 * `ContentResolver.persistedUriPermissions`) matches a numeric cross-user request (`0@...`), while
 * mismatched explicit user IDs (`0@` vs `10@`) fail closed.
 */
fun contentTreeUrisMatch(persistedUri: String, requestedUri: String): Boolean {
    if (persistedUri == requestedUri) return true
    val persistedAuthority = parseContentUriAuthority(persistedUri) ?: return false
    val requestedAuthority = parseContentUriAuthority(requestedUri) ?: return false
    if (persistedAuthority.provider != requestedAuthority.provider) return false
    val sameExplicitUser =
        persistedAuthority.userId == null ||
            requestedAuthority.userId == null ||
            persistedAuthority.userId == requestedAuthority.userId
    if (!sameExplicitUser) return false
    val persistedTree = treeDocumentIdOrNull(persistedUri) ?: return false
    val requestedTree = treeDocumentIdOrNull(requestedUri) ?: return false
    return persistedTree == requestedTree
}

/**
 * Strips a valid numeric `<userId>@` (or `<userId>%40`) prefix from a `content://` URI while
 * preserving the original provider casing and encoded path/query/fragment.
 */
fun stripContentUriUserId(uri: String): String {
    val parsed = parseRawAuthority(uri) ?: return uri
    if (parsed.userId == null) return uri
    return uri.substring(0, CONTENT_URI_SCHEME.length) +
        parsed.rawProvider +
        uri.substring(parsed.authorityEnd)
}

/**
 * Returns `<userId>@<rawProvider>` with an unencoded `@` when [uri] carries a valid numeric
 * cross-user authority, or `null` when it has no user prefix or is malformed.
 */
fun canonicalCrossUserAuthorityOrNull(uri: String): String? {
    val parsed = parseRawAuthority(uri) ?: return null
    val userId = parsed.userId ?: return null
    return "$userId@${parsed.rawProvider}"
}

private fun parseRawAuthority(uri: String): ParsedContentAuthority? {
    if (!uri.startsWith(CONTENT_URI_SCHEME, ignoreCase = true)) return null
    val start = CONTENT_URI_SCHEME.length
    val endIndex = uri.indexOfAny(AUTHORITY_TERMINATORS, startIndex = start)
    val authorityEnd = if (endIndex < 0) uri.length else endIndex
    val rawAuthority = uri.substring(start, authorityEnd)
    if (rawAuthority.isEmpty()) return null
    val normalized = rawAuthority.replace("%40", "@", ignoreCase = true)
    val atIndex = normalized.indexOf('@')
    return if (atIndex < 0) {
        if (!normalized.matches(PROVIDER_AUTHORITY_REGEX)) return null
        ParsedContentAuthority(
            userId = null,
            normalizedProvider = normalized.lowercase(Locale.ROOT),
            rawProvider = rawAuthority,
            authorityEnd = authorityEnd,
        )
    } else {
        val userId = normalized.substring(0, atIndex)
        val provider = normalized.substring(atIndex + 1)
        if (!userId.matches(NUMERIC_USER_ID_REGEX) || !provider.matches(PROVIDER_AUTHORITY_REGEX)) {
            return null
        }
        val separatorLength = if (rawAuthority[atIndex] == '@') 1 else ENCODED_AT_LENGTH
        val rawProvider = rawAuthority.substring(atIndex + separatorLength)
        ParsedContentAuthority(
            userId = userId,
            normalizedProvider = provider.lowercase(Locale.ROOT),
            rawProvider = rawProvider,
            authorityEnd = authorityEnd,
        )
    }
}

private fun treeDocumentIdOrNull(uri: String): String? {
    val withoutQuery = uri.substringBefore('?').substringBefore('#')
    val treeIndex = withoutQuery.indexOf(TREE_PATH_SEGMENT, startIndex = CONTENT_URI_SCHEME.length)
    if (treeIndex < 0) return null
    val afterTree = withoutQuery.substring(treeIndex + TREE_PATH_SEGMENT.length)
    if (afterTree.isEmpty()) return null
    val documentIndex = afterTree.indexOf(DOCUMENT_PATH_SEGMENT)
    val rawId = if (documentIndex >= 0) afterTree.substring(0, documentIndex) else afterTree
    return decodePercentEncodedOrNull(rawId)
}

private fun decodePercentEncodedOrNull(raw: String): String? {
    if (raw.isEmpty()) return null
    if ('%' !in raw) return raw
    val out = ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        if (raw[index] == '%') {
            if (index + PERCENT_ESCAPE_LENGTH > raw.length) return null
            val hi = raw[index + 1].digitToIntOrNull(HEX_RADIX) ?: return null
            val lo = raw[index + 2].digitToIntOrNull(HEX_RADIX) ?: return null
            out.write((hi shl NIBBLE_BITS) or lo)
            index += PERCENT_ESCAPE_LENGTH
        } else {
            val nextPercent = raw.indexOf('%', startIndex = index)
            val sliceEnd = if (nextPercent < 0) raw.length else nextPercent
            val slice = raw.substring(index, sliceEnd).toByteArray(StandardCharsets.UTF_8)
            out.write(slice, 0, slice.size)
            index = sliceEnd
        }
    }
    return String(out.toByteArray(), StandardCharsets.UTF_8).takeIf(String::isNotEmpty)
}
