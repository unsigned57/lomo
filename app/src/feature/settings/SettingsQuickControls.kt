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
        MemoDirectoryRow(subtitle = memoDirectoryLabel, onClick = onMemoDirectoryClick)
        SettingsDivider()
        ImageDirectoryRow(subtitle = imageDirectoryLabel, onClick = onImageDirectoryClick)
        SettingsDivider()
        VoiceDirectoryRow(subtitle = voiceDirectoryLabel, onClick = onVoiceDirectoryClick)
    }
}

@Composable
internal fun SettingsPreferencesQuickGroup(
    themeLabel: String,
    languageLabel: String,
    colorPaletteLabel: String,
    fontLabel: String,
    appLockChecked: Boolean,
    hapticChecked: Boolean,
    onThemeClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onTypographyClick: () -> Unit,
    onColorPaletteClick: () -> Unit,
    onFontClick: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onHapticChange: (Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_home_preferences_section_title)) {
        ThemeModeRow(label = themeLabel, onClick = onThemeClick)
        SettingsDivider()
        ColorPaletteRow(subtitle = colorPaletteLabel, onClick = onColorPaletteClick)
        SettingsDivider()
        FontRow(subtitle = fontLabel, onClick = onFontClick)
        SettingsDivider()
        LanguageRow(label = languageLabel, onClick = onLanguageClick)
        SettingsDivider()
        TypographyRow(onClick = onTypographyClick)
        SettingsDivider()
        AppLockRow(checked = appLockChecked, onCheckedChange = onAppLockChange)
        SettingsDivider()
        HapticRow(checked = hapticChecked, onCheckedChange = onHapticChange)
    }
}
