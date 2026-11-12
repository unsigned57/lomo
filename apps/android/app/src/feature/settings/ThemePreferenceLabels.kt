package com.lomo.app.feature.settings

import com.lomo.domain.model.ThemeMode

internal fun resolveThemeModeSummaryLabel(
    themeMode: ThemeMode,
    pureBlackDark: Boolean,
    themeModeLabels: Map<ThemeMode, String>,
    pureBlackSystemLabel: String,
    pureBlackDarkLabel: String,
): String = when {
    pureBlackDark && themeMode == ThemeMode.SYSTEM -> pureBlackSystemLabel
    pureBlackDark && themeMode == ThemeMode.DARK -> pureBlackDarkLabel
    else -> themeModeLabels[themeMode] ?: themeMode.value
}
