package com.lomo.app.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.lomo.domain.model.ThemeMode

@AppCompatDelegate.NightMode
fun ThemeMode.toAppCompatNightMode(): Int =
    when (this) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

internal fun resolvePlatformNightMode(
    themeMode: ThemeMode,
): Int? =
    when (themeMode) {
        ThemeMode.SYSTEM -> null
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
    }

fun applyAppNightMode(
    context: Context,
    themeMode: ThemeMode,
) {
    val targetCompatMode = themeMode.toAppCompatNightMode()
    if (AppCompatDelegate.getDefaultNightMode() != targetCompatMode) {
        AppCompatDelegate.setDefaultNightMode(targetCompatMode)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        val targetPlatformMode = resolvePlatformNightMode(themeMode) ?: return
        if (uiModeManager.nightMode != targetPlatformMode) {
            uiModeManager.setApplicationNightMode(targetPlatformMode)
        }
    }
}
