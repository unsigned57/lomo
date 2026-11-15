package com.lomo.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem
import com.lomo.ui.icon.LomoIcons

@Composable
internal fun MemoDirectoryRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_memo_directory),
        subtitle = subtitle,
        icon = LomoIcons.FolderOpen,
        onClick = onClick,
    )
}

@Composable
internal fun ImageDirectoryRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_image_storage),
        subtitle = subtitle,
        icon = LomoIcons.PhotoLibrary,
        onClick = onClick,
    )
}

@Composable
internal fun VoiceDirectoryRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_voice_storage),
        subtitle = subtitle,
        icon = LomoIcons.Audiotrack,
        onClick = onClick,
    )
}

@Composable
internal fun ThemeModeRow(label: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_theme_mode),
        subtitle = label,
        icon = LomoIcons.Brightness6,
        onClick = onClick,
    )
}

@Composable
internal fun LanguageRow(label: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_language),
        subtitle = label,
        icon = LomoIcons.Language,
        onClick = onClick,
    )
}

@Composable
internal fun TypographyRow(onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_typography),
        subtitle = stringResource(R.string.settings_typography_subtitle),
        icon = LomoIcons.TextFields,
        onClick = onClick,
    )
}

@Composable
internal fun ColorPaletteRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_color_palette),
        subtitle = subtitle,
        icon = LomoIcons.Palette,
        onClick = onClick,
    )
}

@Composable
internal fun FontRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_font),
        subtitle = subtitle,
        icon = LomoIcons.FontDownload,
        onClick = onClick,
    )
}

@Composable
internal fun AppLockRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_app_lock),
        subtitle = stringResource(R.string.settings_app_lock_subtitle),
        icon = LomoIcons.Lock,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun HapticRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_haptic_feedback),
        subtitle = stringResource(R.string.settings_haptic_feedback_subtitle),
        icon = LomoIcons.Vibration,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
