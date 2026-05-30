package com.lomo.domain.model

/**
 * Identifier of a curated preset palette. The [value] is stable across versions and persisted via
 * [ColorSource.Preset.storageValue]; do not rename existing entries.
 *
 * Seed colours live here (domain) rather than in `ui-components` so that data-layer
 * tests, sync payloads, and future export/import flows can validate preset IDs without pulling in
 * Compose.
 */
enum class ColorPresetId(
    val value: String,
    val seedArgb: Int,
) {
    INDIGO("indigo", PresetSeeds.INDIGO),
    FOREST("forest", PresetSeeds.FOREST),
    OCEAN("ocean", PresetSeeds.OCEAN),
    SUNSET("sunset", PresetSeeds.SUNSET),
    LAVENDER("lavender", PresetSeeds.LAVENDER),
    ROSE("rose", PresetSeeds.ROSE),
    SAND("sand", PresetSeeds.SAND),
    ;

    companion object {
        fun fromValue(value: String?): ColorPresetId? = entries.firstOrNull { it.value == value }
    }
}

private object PresetSeeds {
    const val INDIGO: Int = 0xFF4F63D6.toInt()
    const val FOREST: Int = 0xFF3F7D44.toInt()
    const val OCEAN: Int = 0xFF00798C.toInt()
    const val SUNSET: Int = 0xFFD9531E.toInt()
    const val LAVENDER: Int = 0xFF7A5BD9.toInt()
    const val ROSE: Int = 0xFFC2185B.toInt()
    const val SAND: Int = 0xFFB68A4D.toInt()
}
