package com.lomo.app.theme

import android.content.res.Configuration
import com.lomo.domain.model.ThemeMode

internal object ThemeResyncPolicy {
    fun shouldResyncOnResume(themeMode: ThemeMode): Boolean =
        when (themeMode) {
            ThemeMode.SYSTEM -> false
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> false
        }

    fun shouldResyncOnConfigurationChange(
        themeMode: ThemeMode,
        previousUiMode: Int,
        currentUiMode: Int,
    ): Boolean {
        if (themeMode != ThemeMode.SYSTEM) return false

        val previousNightMask = previousUiMode and Configuration.UI_MODE_NIGHT_MASK
        val currentNightMask = currentUiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentNightMask == Configuration.UI_MODE_NIGHT_UNDEFINED) {
            return false
        }
        return previousNightMask != currentNightMask
    }
}
