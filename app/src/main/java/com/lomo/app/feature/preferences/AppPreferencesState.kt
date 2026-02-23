package com.lomo.app.feature.preferences

import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Aggregated UI preferences consumed by multiple screens.
 */
data class AppPreferencesState(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: String,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val shareCardStyle: String,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
) {
    companion object {
        fun defaults(): AppPreferencesState =
            AppPreferencesState(
                dateFormat = PreferenceKeys.Defaults.DATE_FORMAT,
                timeFormat = PreferenceKeys.Defaults.TIME_FORMAT,
                themeMode = PreferenceKeys.Defaults.THEME_MODE,
                hapticFeedbackEnabled = PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED,
                showInputHints = PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
                doubleTapEditEnabled = PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED,
                shareCardStyle = PreferenceKeys.Defaults.SHARE_CARD_STYLE,
                shareCardShowTime = PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME,
                shareCardShowBrand = PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND,
            )
    }
}

fun SettingsRepository.observeAppPreferences(): Flow<AppPreferencesState> =
    combine(
        combine(
            getDateFormat(),
            getTimeFormat(),
            getThemeMode(),
            isHapticFeedbackEnabled(),
            isShowInputHintsEnabled(),
        ) { dateFormat, timeFormat, themeMode, hapticFeedbackEnabled, showInputHints ->
            BasePreferences(
                dateFormat = dateFormat,
                timeFormat = timeFormat,
                themeMode = themeMode,
                hapticFeedbackEnabled = hapticFeedbackEnabled,
                showInputHints = showInputHints,
            )
        },
        combine(
            isDoubleTapEditEnabled(),
            getShareCardStyle(),
            isShareCardShowTimeEnabled(),
            isShareCardShowBrandEnabled(),
        ) { doubleTapEditEnabled, shareCardStyle, shareCardShowTime, shareCardShowBrand ->
            SharePreferences(
                doubleTapEditEnabled = doubleTapEditEnabled,
                shareCardStyle = shareCardStyle,
                shareCardShowTime = shareCardShowTime,
                shareCardShowBrand = shareCardShowBrand,
            )
        },
    ) { base, share ->
        AppPreferencesState(
            dateFormat = base.dateFormat,
            timeFormat = base.timeFormat,
            themeMode = base.themeMode,
            hapticFeedbackEnabled = base.hapticFeedbackEnabled,
            showInputHints = base.showInputHints,
            doubleTapEditEnabled = share.doubleTapEditEnabled,
            shareCardStyle = share.shareCardStyle,
            shareCardShowTime = share.shareCardShowTime,
            shareCardShowBrand = share.shareCardShowBrand,
        )
    }

private data class BasePreferences(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: String,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
)

private data class SharePreferences(
    val doubleTapEditEnabled: Boolean,
    val shareCardStyle: String,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
)
