package com.lomo.domain.model

private const val HEX_RADIX = 16
private const val HEX_RGB_LENGTH = 6
private const val ARGB_OPAQUE_ALPHA: Int = 0xFF
private const val ARGB_ALPHA_SHIFT = 24
private const val ARGB_RGB_MASK = 0xFFFFFF

private const val STORAGE_DYNAMIC = "dynamic"
private const val STORAGE_PRESET_PREFIX = "preset:"
private const val STORAGE_SEED_PREFIX = "seed:#"

/**
 * Single source of truth for where the app's Material 3 ColorScheme hues come from.
 *
 * Replaces the legacy `dynamicColor: Boolean` switch on `LomoTheme`. All consumers must route through
 * this model — there is no parallel "raw boolean" path.
 */
sealed interface ColorSource {
    val storageValue: String

    /**
     * Material You wallpaper extraction. Only resolvable on Android 12+; lower SDKs must fall back at
     * the theme layer (see `LomoTheme`) rather than swapping the model.
     */
    data object DynamicWallpaper : ColorSource {
        override val storageValue: String = STORAGE_DYNAMIC
    }

    /** A curated, hand-picked palette identified by its [ColorPresetId]. */
    data class Preset(val id: ColorPresetId) : ColorSource {
        override val storageValue: String = STORAGE_PRESET_PREFIX + id.value
    }

    /**
     * A user-picked seed colour. Full M3 tonal palette is derived from this at the UI layer.
     * [argb] is the canonical 32-bit ARGB representation with alpha forced to opaque.
     */
    data class CustomSeed(val argb: Int) : ColorSource {
        override val storageValue: String = STORAGE_SEED_PREFIX + formatRgbHex(argb)
    }

    companion object {
        fun default(): ColorSource = DynamicWallpaper

        /**
         * Parses a persisted storage value back into a [ColorSource].
         *
         * Unknown/corrupt strings collapse to [default]. This is a documented domain state, not a
         * silent-error fallback: any value the app cannot interpret has the same observable effect as
         * "no preference recorded", which is also the user's first-launch state.
         */
        fun fromStorageValue(value: String?): ColorSource {
            if (value.isNullOrBlank()) return default()
            return fromStorageValueOrNull(value) ?: default()
        }

        fun fromStorageValueOrNull(value: String): ColorSource? {
            if (value == STORAGE_DYNAMIC) return DynamicWallpaper
            if (value.startsWith(STORAGE_PRESET_PREFIX)) {
                val id = ColorPresetId.fromValue(value.removePrefix(STORAGE_PRESET_PREFIX))
                return id?.let(::Preset)
            }
            if (value.startsWith(STORAGE_SEED_PREFIX)) {
                val rgb = parseRgbHex(value.removePrefix(STORAGE_SEED_PREFIX))
                return rgb?.let { CustomSeed(asOpaqueArgb(it)) }
            }
            return null
        }
    }
}

/**
 * Forces an arbitrary 32-bit color into the opaque ARGB form used by [ColorSource.CustomSeed].
 */
fun asOpaqueArgb(rgb: Int): Int = (ARGB_OPAQUE_ALPHA shl ARGB_ALPHA_SHIFT) or (rgb and ARGB_RGB_MASK)

private fun formatRgbHex(argb: Int): String {
    val rgb = argb and ARGB_RGB_MASK
    return rgb.toString(HEX_RADIX).padStart(HEX_RGB_LENGTH, '0').uppercase()
}

private fun parseRgbHex(hex: String): Int? {
    if (hex.length != HEX_RGB_LENGTH) return null
    if (hex.any { !it.isHexDigit() }) return null
    return hex.toInt(HEX_RADIX)
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
