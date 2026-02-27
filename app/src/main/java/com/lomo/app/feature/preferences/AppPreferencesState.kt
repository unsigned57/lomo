package com.lomo.app.feature.preferences

import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.PreferencesRepository
import com.lomo.ui.util.stateInViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Aggregated UI preferences consumed by multiple screens.
 */
data class AppPreferencesState(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val shareCardStyle: ShareCardStyle,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
) {
    companion object {
        fun defaults(): AppPreferencesState =
            AppPreferencesState(
                dateFormat = PreferenceKeys.Defaults.DATE_FORMAT,
                timeFormat = PreferenceKeys.Defaults.TIME_FORMAT,
                themeMode = ThemeMode.SYSTEM,
                hapticFeedbackEnabled = PreferenceKeys.Defaults.HAPTIC_FEEDBACK_ENABLED,
                showInputHints = PreferenceKeys.Defaults.SHOW_INPUT_HINTS,
                doubleTapEditEnabled = PreferenceKeys.Defaults.DOUBLE_TAP_EDIT_ENABLED,
                shareCardStyle = ShareCardStyle.CLEAN,
                shareCardShowTime = PreferenceKeys.Defaults.SHARE_CARD_SHOW_TIME,
                shareCardShowBrand = PreferenceKeys.Defaults.SHARE_CARD_SHOW_BRAND,
            )
    }
}

fun PreferencesRepository.observeAppPreferences(): Flow<AppPreferencesState> =
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

fun PreferencesRepository.appPreferencesState(scope: CoroutineScope): StateFlow<AppPreferencesState> =
    observeAppPreferences()
        .stateInViewModel(scope, AppPreferencesState.defaults())

fun MemoRepository.activeDayCountState(scope: CoroutineScope): StateFlow<Int> =
    getActiveDayCount()
        .stateInViewModel(scope, 0)

private data class BasePreferences(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
)

private data class SharePreferences(
    val doubleTapEditEnabled: Boolean,
    val shareCardStyle: ShareCardStyle,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
)
