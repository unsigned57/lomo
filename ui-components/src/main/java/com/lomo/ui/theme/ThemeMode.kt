package com.lomo.ui.theme

import java.util.Locale

enum class ThemeMode(
    val storageValue: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode {
            val normalized = value?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.storageValue == normalized } ?: SYSTEM
        }
    }
}
