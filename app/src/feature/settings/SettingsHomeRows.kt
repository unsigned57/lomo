package com.lomo.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lomo.app.R
import com.lomo.ui.component.settings.PreferenceItem
import com.lomo.ui.component.settings.SwitchPreferenceItem

@Composable
internal fun MemoDirectoryRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_memo_directory),
        subtitle = subtitle,
        icon = Icons.Outlined.FolderOpen,
        onClick = onClick,
    )
}

@Composable
internal fun ImageDirectoryRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_image_storage),
        subtitle = subtitle,
        icon = Icons.Outlined.PhotoLibrary,
        onClick = onClick,
    )
}

@Composable
internal fun VoiceDirectoryRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_voice_storage),
        subtitle = subtitle,
        icon = Icons.Outlined.Audiotrack,
        onClick = onClick,
    )
}

@Composable
internal fun ThemeModeRow(label: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_theme_mode),
        subtitle = label,
        icon = Icons.Outlined.Brightness6,
        onClick = onClick,
    )
}

@Composable
internal fun LanguageRow(label: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_language),
        subtitle = label,
        icon = Icons.Outlined.Language,
        onClick = onClick,
    )
}

@Composable
internal fun TypographyRow(onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_typography),
        subtitle = stringResource(R.string.settings_typography_subtitle),
        icon = Icons.Outlined.TextFields,
        onClick = onClick,
    )
}

@Composable
internal fun ColorPaletteRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_color_palette),
        subtitle = subtitle,
        icon = Icons.Outlined.ColorLens,
        onClick = onClick,
    )
}

@Composable
internal fun FontRow(subtitle: String, onClick: () -> Unit) {
    PreferenceItem(
        title = stringResource(R.string.settings_font),
        subtitle = subtitle,
        icon = Icons.Outlined.FontDownload,
        onClick = onClick,
    )
}

@Composable
internal fun AppLockRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_app_lock),
        subtitle = stringResource(R.string.settings_app_lock_subtitle),
        icon = Icons.Outlined.Lock,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun HapticRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreferenceItem(
        title = stringResource(R.string.settings_haptic_feedback),
        subtitle = stringResource(R.string.settings_haptic_feedback_subtitle),
        icon = Icons.Outlined.Vibration,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
