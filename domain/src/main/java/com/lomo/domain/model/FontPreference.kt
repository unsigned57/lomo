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
     * leaves the data layer.
     */
    data class UserImported(val id: String) : FontPreference {
        override val storageValue: String = STORAGE_CUSTOM_PREFIX + id
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
            if (value == STORAGE_SYSTEM) return SystemDefault
            if (value.startsWith(STORAGE_CUSTOM_PREFIX)) {
                val id = value.removePrefix(STORAGE_CUSTOM_PREFIX).trim()
                if (id.isEmpty() || id.contains('/') || id.contains('\\')) return default()
                return UserImported(id)
            }
            return default()
        }
    }
}
