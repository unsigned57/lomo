package com.lomo.app.feature.main

import java.util.Locale

private val MEMO_GEO_URI_REGEX =
    Regex(
        pattern = """(?<![\w\[\(])(geo:-?\d+(?:\.\d+)?,-?\d+(?:\.\d+)?(?:\?z=\d+)?)""",
        option = RegexOption.IGNORE_CASE,
    )
private val MEMO_GEO_COORDINATE_PAIR_REGEX = Regex("""-?\d+(?:\.\d+)?,-?\d+(?:\.\d+)?""")
private val MEMO_GEO_ZOOM_REGEX = Regex("""[?&]z=(\d+)""", RegexOption.IGNORE_CASE)
internal const val DEFAULT_MEMO_GEO_ZOOM = 10

internal fun appendLegacyMemoGeoLocation(
    content: String,
    geoLocation: String?,
): String {
    val normalizedGeoUri = normalizeMemoGeoUri(geoLocation) ?: return content
    if (content.contains(normalizedGeoUri, ignoreCase = true)) {
        return content
    }
    return if (content.isBlank()) {
        normalizedGeoUri
    } else {
        "$content\n$normalizedGeoUri"
    }
}

internal fun formatMemoGeoUri(
    latitude: Double,
    longitude: Double,
): String = "geo:${formatMemoGeoCoordinate(latitude)},${formatMemoGeoCoordinate(longitude)}?z=$DEFAULT_MEMO_GEO_ZOOM"

internal fun normalizeMemoGeoUri(value: String?): String? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    val compact = trimmed.replace(" ", "")
    if (compact.startsWith("geo:", ignoreCase = true)) {
        val coordinatePart = compact.substringAfter(':').substringBefore('?')
        if (!MEMO_GEO_COORDINATE_PAIR_REGEX.matches(coordinatePart)) {
            return null
        }
        val zoom = MEMO_GEO_ZOOM_REGEX.find(compact)?.groupValues?.get(1) ?: DEFAULT_MEMO_GEO_ZOOM.toString()
        return "geo:$coordinatePart?z=$zoom"
    }
    return if (MEMO_GEO_COORDINATE_PAIR_REGEX.matches(compact)) {
        "geo:$compact?z=$DEFAULT_MEMO_GEO_ZOOM"
    } else {
        null
    }
}

internal fun linkifyMemoGeoUris(content: String): String =
    MEMO_GEO_URI_REGEX.replace(content) { match ->
        val normalizedGeoUri = normalizeMemoGeoUri(match.value) ?: return@replace match.value
        "[${match.value}]($normalizedGeoUri)"
    }

private fun formatMemoGeoCoordinate(value: Double): String = String.format(Locale.US, "%.4f", value)
