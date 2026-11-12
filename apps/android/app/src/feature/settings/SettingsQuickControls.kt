package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.SettingsGroup

@Composable
internal fun SettingsStorageQuickGroup(
    memoDirectoryLabel: String,
    imageDirectoryLabel: String,
    voiceDirectoryLabel: String,
    onMemoDirectoryClick: () -> Unit,
    onImageDirectoryClick: () -> Unit,
    onVoiceDirectoryClick: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_home_storage_section_title)) {
        item { MemoDirectoryRow(subtitle = memoDirectoryLabel, onClick = onMemoDirectoryClick) }
        item { ImageDirectoryRow(subtitle = imageDirectoryLabel, onClick = onImageDirectoryClick) }
        item { VoiceDirectoryRow(subtitle = voiceDirectoryLabel, onClick = onVoiceDirectoryClick) }
    }
}

@Composable
internal fun SettingsPreferencesQuickGroup(
    themeLabel: String,
    languageLabel: String,
    colorPaletteLabel: String,
    fontLabel: String,
    onThemeClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onTypographyClick: () -> Unit,
    onColorPaletteClick: () -> Unit,
    onFontClick: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_home_preferences_section_title)) {
        item { ThemeModeRow(label = themeLabel, onClick = onThemeClick) }
        item { ColorPaletteRow(subtitle = colorPaletteLabel, onClick = onColorPaletteClick) }
        item { FontRow(subtitle = fontLabel, onClick = onFontClick) }
        item { TypographyRow(onClick = onTypographyClick) }
        item { LanguageRow(label = languageLabel, onClick = onLanguageClick) }
    }
}
