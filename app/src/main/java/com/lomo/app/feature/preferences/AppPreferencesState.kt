package com.lomo.app.feature.preferences

import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
    val freeTextCopyEnabled: Boolean,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardStyle: ShareCardStyle,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
) {
    companion object {
        fun defaults(): AppPreferencesState =
            AppPreferencesState(
                dateFormat = PreferenceDefaults.DATE_FORMAT,
                timeFormat = PreferenceDefaults.TIME_FORMAT,
                themeMode = ThemeMode.SYSTEM,
                hapticFeedbackEnabled = PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED,
                showInputHints = PreferenceDefaults.SHOW_INPUT_HINTS,
                doubleTapEditEnabled = PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED,
                freeTextCopyEnabled = PreferenceDefaults.FREE_TEXT_COPY_ENABLED,
                quickSaveOnBackEnabled = PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED,
                shareCardStyle = ShareCardStyle.CLEAN,
                shareCardShowTime = PreferenceDefaults.SHARE_CARD_SHOW_TIME,
                shareCardShowBrand = PreferenceDefaults.SHARE_CARD_SHOW_BRAND,
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
            isFreeTextCopyEnabled(),
            isQuickSaveOnBackEnabled(),
            getShareCardStyle(),
            isShareCardShowTimeEnabled(),
        ) { doubleTapEditEnabled, freeTextCopyEnabled, quickSaveOnBackEnabled, shareCardStyle, shareCardShowTime ->
            SharePreferences(
                doubleTapEditEnabled = doubleTapEditEnabled,
                freeTextCopyEnabled = freeTextCopyEnabled,
                quickSaveOnBackEnabled = quickSaveOnBackEnabled,
                shareCardStyle = shareCardStyle,
                shareCardShowTime = shareCardShowTime,
            )
        },
        isShareCardShowBrandEnabled(),
    ) { base, share, shareCardShowBrand ->
        AppPreferencesState(
            dateFormat = base.dateFormat,
            timeFormat = base.timeFormat,
            themeMode = base.themeMode,
            hapticFeedbackEnabled = base.hapticFeedbackEnabled,
            showInputHints = base.showInputHints,
            doubleTapEditEnabled = share.doubleTapEditEnabled,
            freeTextCopyEnabled = share.freeTextCopyEnabled,
            quickSaveOnBackEnabled = share.quickSaveOnBackEnabled,
            shareCardStyle = share.shareCardStyle,
            shareCardShowTime = share.shareCardShowTime,
            shareCardShowBrand = shareCardShowBrand,
        )
    }

fun PreferencesRepository.appPreferencesState(scope: CoroutineScope): StateFlow<AppPreferencesState> =
    observeAppPreferences()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000L), AppPreferencesState.defaults())

fun MemoRepository.activeDayCountState(scope: CoroutineScope): StateFlow<Int> =
    getActiveDayCount()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000L), 0)

private data class BasePreferences(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
)

private data class SharePreferences(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardStyle: ShareCardStyle,
    val shareCardShowTime: Boolean,
)
