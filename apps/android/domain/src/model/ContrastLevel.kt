package com.lomo.domain.model

private const val MEDIUM_CONTRAST_VALUE = 0.5

/**
 * Material 3 contrast tier applied to the seed-generated tonal palette.
 *
 * The stored value maps onto Material Color Utilities `contrastLevel` (-1.0..1.0), which the UI
 * layer feeds to the seed-derived color scheme. Dynamic wallpaper palettes are produced by the platform and do
 * not consume this setting — the model still persists uniformly for every [ColorSource].
 */
enum class ContrastLevel(val value: String, val contrastValue: Double) {
    STANDARD("standard", 0.0),
    MEDIUM("medium", MEDIUM_CONTRAST_VALUE),
    HIGH("high", 1.0),
    ;

    companion object {
        fun default(): ContrastLevel = STANDARD

        fun fromStorageValue(value: String?): ContrastLevel = fromStorageValueOrNull(value) ?: default()

        fun fromStorageValueOrNull(value: String?): ContrastLevel? = entries.find { it.value == value }
    }
}
