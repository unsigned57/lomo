package com.lomo.domain.model

private const val STORAGE_SYSTEM = "system"
private const val STORAGE_CUSTOM_PREFIX = "custom:"

/**
 * Single source of truth for the typeface family the app renders text in. Persistence is a stable
 * opaque string; the UI layer turns this into a Compose `FontFamily` (see `FontFamilyResolver`).
 *
 * Replaces the hardcoded `FontFamily.SansSerif` reference inside the legacy `Typography` constant —
 * there is no parallel "raw FontFamily" path through the theme layer.
 */
sealed interface FontPreference {
    val storageValue: String

    /** Use the platform sans-serif (variable-font pipeline on Android 12+). */
    data object SystemDefault : FontPreference {
        override val storageValue: String = STORAGE_SYSTEM
    }

    /**
     * Use a user-imported `.ttf` / `.otf` font. [id] is the filename of the imported font under
     * the app's private `custom_fonts` directory; it never contains a path separator and never
     * leaves the data layer. [axisOverrides] stores optional OpenType variable-axis coordinate
     * values (`tag -> coordinate`).
     */
    data class UserImported(val id: String, val axisOverrides: Map<String, Float> = emptyMap()) : FontPreference {
        override val storageValue: String = formatUserImportedStorageValue(id, axisOverrides)
    }

    companion object {
        fun default(): FontPreference = SystemDefault

        /**
         * Parses a persisted storage value back into a [FontPreference].
         *
         * Unknown/corrupt strings collapse to [default]. This is a documented domain state, not a
         * silent-error fallback: any value the app cannot interpret has the same observable effect
         * as "no preference recorded" (the first-launch state).
         */
        fun fromStorageValue(value: String?): FontPreference {
            if (value.isNullOrBlank()) return default()
            return fromStorageValueOrNull(value) ?: default()
        }

        fun fromStorageValueOrNull(value: String): FontPreference? {
            if (value == STORAGE_SYSTEM) return SystemDefault
            if (!value.startsWith(STORAGE_CUSTOM_PREFIX)) return null

            val payload = value.removePrefix(STORAGE_CUSTOM_PREFIX)
            val querySeparator = payload.indexOf('?')
            val rawId =
                if (querySeparator >= 0) {
                    payload.substring(0, querySeparator)
                } else {
                    payload
                }
            val id = rawId.trim()
            if (!isSafeFontId(id)) return null

            if (querySeparator < 0) return UserImported(id)
            val query = payload.substring(querySeparator + 1)
            val axes = parseAxisQueryOrNull(query) ?: return null
            return UserImported(id = id, axisOverrides = axes)
        }

        private fun isSafeFontId(id: String): Boolean = id.isNotEmpty() &&
            !id.contains('/') &&
            !id.contains('\\') &&
            !id.contains('?') &&
            !id.contains('#') &&
            !id.contains("..")

        private fun parseAxisQueryOrNull(query: String): Map<String, Float>? {
            if (query.isEmpty()) return null
            val parsed = sortedMapOf<String, Float>()
            for (segment in query.split('&')) {
                val eqIndex = segment.indexOf('=')
                if (eqIndex <= 0 || eqIndex == segment.lastIndex) return null
                val tag = segment.substring(0, eqIndex)
                if (!FontVariationAxis.isValidTag(tag) || tag in parsed) return null
                val coordinate = segment.substring(eqIndex + 1).toFloatOrNull()?.takeIf(Float::isFinite)
                    ?: return null
                parsed[tag] = coordinate
            }
            return parsed.toMap()
        }

        private fun formatUserImportedStorageValue(id: String, axisOverrides: Map<String, Float>): String {
            if (axisOverrides.isEmpty()) return STORAGE_CUSTOM_PREFIX + id
            val encodedAxes =
                axisOverrides.entries
                    .asSequence()
                    .filter { (tag, value) -> FontVariationAxis.isValidTag(tag) && value.isFinite() }
                    .sortedBy { it.key }
                    .joinToString(separator = "&") { (tag, value) -> "$tag=${formatCoordinate(value)}" }
            return if (encodedAxes.isEmpty()) {
                STORAGE_CUSTOM_PREFIX + id
            } else {
                "$STORAGE_CUSTOM_PREFIX$id?$encodedAxes"
            }
        }

        private fun formatCoordinate(value: Float): String {
            val normalized = if (value == 0f) 0f else value
            val truncated = normalized.toInt()
            return if (normalized == truncated.toFloat()) truncated.toString() else normalized.toString()
        }
    }
}
