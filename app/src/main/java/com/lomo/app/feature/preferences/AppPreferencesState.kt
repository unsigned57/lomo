package com.lomo.app.feature.preferences

import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: List<String>,
    val quickSaveOnBackEnabled: Boolean,
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
                memoActionAutoReorderEnabled = PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED,
                memoActionOrder = emptyList(),
                quickSaveOnBackEnabled = PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED,
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
            combine(
                isDoubleTapEditEnabled(),
                isFreeTextCopyEnabled(),
                isMemoActionAutoReorderEnabled(),
                getMemoActionOrder(),
            ) {
                doubleTapEditEnabled,
                freeTextCopyEnabled,
                memoActionAutoReorderEnabled,
                memoActionOrder,
                ->
                MemoActionPreferences(
                    doubleTapEditEnabled = doubleTapEditEnabled,
                    freeTextCopyEnabled = freeTextCopyEnabled,
                    memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
                    memoActionOrder = memoActionOrder,
                )
            },
            isQuickSaveOnBackEnabled(),
            isShareCardShowTimeEnabled(),
        ) { memoAction, quickSaveOnBackEnabled, shareCardShowTime ->
            SharePreferences(
                doubleTapEditEnabled = memoAction.doubleTapEditEnabled,
                freeTextCopyEnabled = memoAction.freeTextCopyEnabled,
                memoActionAutoReorderEnabled = memoAction.memoActionAutoReorderEnabled,
                memoActionOrder = memoAction.memoActionOrder,
                quickSaveOnBackEnabled = quickSaveOnBackEnabled,
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
            memoActionAutoReorderEnabled = share.memoActionAutoReorderEnabled,
            memoActionOrder = share.memoActionOrder,
            quickSaveOnBackEnabled = share.quickSaveOnBackEnabled,
            shareCardShowTime = share.shareCardShowTime,
            shareCardShowBrand = shareCardShowBrand,
        )
    }

fun PreferencesRepository.appPreferencesState(scope: CoroutineScope): StateFlow<AppPreferencesState> =
    observeAppPreferences()
        .stateIn(scope, appWhileSubscribed(), AppPreferencesState.defaults())

fun MemoRepository.activeDayCountState(scope: CoroutineScope): StateFlow<Int> =
    getActiveDayCount()
        .stateIn(scope, appWhileSubscribed(), 0)

private data class BasePreferences(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
)

private data class MemoActionPreferences(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: List<String>,
)

private data class SharePreferences(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: List<String>,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardShowTime: Boolean,
)
