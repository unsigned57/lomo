package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.domain.model.FontPreference

@Composable
internal fun fontPreferenceSummaryLabel(display: DisplaySectionState): String =
    when (val preference = display.fontPreference) {
        is FontPreference.SystemDefault -> stringResource(R.string.settings_font_system_default)
        is FontPreference.UserImported ->
            display.availableCustomFonts.firstOrNull { it.id == preference.id }?.displayName
                ?: stringResource(R.string.settings_font_system_default)
    }
