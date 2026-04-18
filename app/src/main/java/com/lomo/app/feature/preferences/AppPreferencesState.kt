package com.lomo.app.feature.preferences

import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.MemoRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
    val memoActionOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
    val shareCardSignatureText: String = PreferenceDefaults.SHARE_CARD_SIGNATURE_TEXT,
    val typographyFontSizeScale: Float = PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE,
    val typographyLineHeightScale: Float = PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE,
    val typographyLetterSpacingScale: Float = PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE,
    val typographyParagraphSpacingScale: Float = PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
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
                memoActionOrder = persistentListOf(),
                quickSaveOnBackEnabled = PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED,
                scrollbarEnabled = PreferenceDefaults.SCROLLBAR_ENABLED,
                shareCardShowTime = PreferenceDefaults.SHARE_CARD_SHOW_TIME,
                shareCardShowBrand = PreferenceDefaults.SHARE_CARD_SHOW_BRAND,
                shareCardSignatureText = PreferenceDefaults.SHARE_CARD_SIGNATURE_TEXT,
                typographyFontSizeScale = PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE,
                typographyLineHeightScale = PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE,
                typographyLetterSpacingScale = PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE,
                typographyParagraphSpacingScale = PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
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
                    memoActionOrder = memoActionOrder.toImmutableList(),
                )
            },
            isQuickSaveOnBackEnabled(),
            isShareCardShowTimeEnabled(),
            isScrollbarEnabled(),
        ) { memoAction, quickSaveOnBackEnabled, shareCardShowTime, scrollbarEnabled ->
            SharePreferences(
                doubleTapEditEnabled = memoAction.doubleTapEditEnabled,
                freeTextCopyEnabled = memoAction.freeTextCopyEnabled,
                memoActionAutoReorderEnabled = memoAction.memoActionAutoReorderEnabled,
                memoActionOrder = memoAction.memoActionOrder,
                quickSaveOnBackEnabled = quickSaveOnBackEnabled,
                shareCardShowTime = shareCardShowTime,
                scrollbarEnabled = scrollbarEnabled,
            )
        },
        isShareCardShowBrandEnabled(),
        getShareCardSignatureText(),
        combine(
            getFontSizeScale(),
            getLineHeightScale(),
            getLetterSpacingScale(),
            getParagraphSpacingScale(),
        ) { fontSize, lineHeight, letterSpacing, paragraphSpacing ->
            TypographyPreferences(
                fontSizeScale = fontSize,
                lineHeightScale = lineHeight,
                letterSpacingScale = letterSpacing,
                paragraphSpacingScale = paragraphSpacing,
            )
        }
    ) { base, share, shareCardShowBrand, shareCardSignatureText, typography ->
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
            scrollbarEnabled = share.scrollbarEnabled,
            shareCardShowTime = share.shareCardShowTime,
            shareCardShowBrand = shareCardShowBrand,
            shareCardSignatureText = shareCardSignatureText,
            typographyFontSizeScale = typography.fontSizeScale,
            typographyLineHeightScale = typography.lineHeightScale,
            typographyLetterSpacingScale = typography.letterSpacingScale,
            typographyParagraphSpacingScale = typography.paragraphSpacingScale,
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
    val memoActionOrder: ImmutableList<String>,
)

private data class SharePreferences(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val scrollbarEnabled: Boolean,
)

private data class TypographyPreferences(
    val fontSizeScale: Float,
    val lineHeightScale: Float,
    val letterSpacingScale: Float,
    val paragraphSpacingScale: Float,
)
