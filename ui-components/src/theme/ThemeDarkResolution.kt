package com.lomo.ui.theme

import android.content.res.Configuration

internal fun resolveDarkTheme(
    themeMode: ThemeMode,
    currentUiMode: Int?,
    systemDarkTheme: Boolean,
): Boolean =
    when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM ->
            when (currentUiMode?.and(Configuration.UI_MODE_NIGHT_MASK)) {
                Configuration.UI_MODE_NIGHT_YES -> true
                Configuration.UI_MODE_NIGHT_NO -> false
                else -> systemDarkTheme
            }
    }
