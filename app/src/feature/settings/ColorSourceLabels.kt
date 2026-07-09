package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource

@Composable
internal fun colorSourceSummaryLabel(source: ColorSource): String =
    when (source) {
        is ColorSource.DynamicWallpaper -> stringResource(R.string.settings_color_source_dynamic)
        is ColorSource.Preset -> presetDisplayName(source.id)
        is ColorSource.CustomSeed -> "#%06X".format(source.argb and COLOR_PICKER_RGB_MASK)
    }

@Composable
internal fun presetDisplayName(id: ColorPresetId): String =
    when (id) {
        ColorPresetId.INDIGO -> stringResource(R.string.settings_color_preset_indigo)
        ColorPresetId.FOREST -> stringResource(R.string.settings_color_preset_forest)
        ColorPresetId.OCEAN -> stringResource(R.string.settings_color_preset_ocean)
        ColorPresetId.SUNSET -> stringResource(R.string.settings_color_preset_sunset)
        ColorPresetId.LAVENDER -> stringResource(R.string.settings_color_preset_lavender)
        ColorPresetId.ROSE -> stringResource(R.string.settings_color_preset_rose)
        ColorPresetId.SAND -> stringResource(R.string.settings_color_preset_sand)
    }
