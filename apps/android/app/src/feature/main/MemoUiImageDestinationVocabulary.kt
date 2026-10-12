package com.lomo.app.feature.main

import android.net.Uri
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Managed attachment roots a bare destination may claim — Kotlin mirror of the
 * `lomo-workspace` `ATTACHMENT_LOOKUP_DIRS` vocabulary (media/images/voice).
 * Bare basenames pool candidates at the workspace root plus every one of these
 * roots; qualified destinations never consult the vocabulary.
 */
internal val MANAGED_ATTACHMENT_ROOTS = listOf("media", "images", "voice")

/**
 * Canonical fold mirroring `lomo-workspace` `canonicalize_attachment_path`:
 * `.`/empty segments collapse, `..` pops one segment, and a `..` escaping the
 * workspace root rejects — the destination is not a workspace file.
 */
internal fun canonicalizeAttachmentPath(path: String): String? {
    val segments = mutableListOf<String>()
    for (segment in path.split(PATH_SEPARATOR)) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return if (segments.isEmpty()) null else segments.joinToString("$PATH_SEPARATOR")
}

/**
 * The image-map keys a destination may legitimately claim: exact keys for each
 * canonical spelling variant, plus same-basename keys under managed roots when
 * the destination is a bare name. This is the single source both the resolver's
 * candidate pool and the dependency signature use, so the map surface a memo
 * depends on is exactly the map surface it can resolve through.
 */
internal fun imageMapLookupKeys(
    destination: String,
    mapKeys: Set<String>,
): List<String> {
    val hits = LinkedHashSet<String>()
    for (spelling in destinationSpellingVariants(destination)) {
        val canonical = canonicalizeAttachmentPath(spelling) ?: continue
        if (canonical in mapKeys) {
            hits += canonical
        }
        if (PATH_SEPARATOR !in canonical) {
            val suffix = "$PATH_SEPARATOR$canonical"
            mapKeys.filterTo(hits) { key ->
                key.endsWith(suffix) &&
                    key.substringBefore(PATH_SEPARATOR) in MANAGED_ATTACHMENT_ROOTS
            }
        }
    }
    return hits.toList()
}

/** `key=uri` lines a destination depends on — the resolvable map surface. */
internal fun imageMapLookupLines(
    destination: String,
    imageMap: Map<String, Uri>,
): List<String> =
    imageMapLookupKeys(destination, imageMap.keys)
        .map { key -> "$key=${imageMap[key]}" }

/** `key=uri` signature a destination depends on — sorted for stable caching. */
internal fun imageMapLookupSignature(
    destination: String,
    imageMap: Map<String, Uri>,
): String =
    imageMapLookupLines(destination, imageMap)
        .sorted()
        .joinToString("\n")

/**
 * Spelling variants of one authored destination: the raw spelling, its URL
 * decoding, and both with query/fragment stripped. Directory structure is never
 * altered here — basename/parent-stripping lanes are the resolved breach.
 */
private fun destinationSpellingVariants(raw: String): List<String> {
    val normalized = normalizeImageUrl(raw)
    if (normalized.isBlank()) {
        return emptyList()
    }
    val noQuery =
        normalized
            .substringBefore(QUERY_SEPARATOR)
            .substringBefore(FRAGMENT_SEPARATOR)
    return listOf(
        normalized,
        decodeUrlComponent(normalized),
        noQuery,
        decodeUrlComponent(noQuery),
    ).distinct()
}

internal fun decodeUrlComponent(value: String): String =
    // behavior-contract: silent-result-ok: malformed percent-encoding is still a lookup token
    runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

internal fun normalizeImageUrl(raw: String): String =
    raw
        .trim()
        .removeSurrounding("<", ">")
        .replace('\\', PATH_SEPARATOR)
