package com.lomo.app.feature.preferences

import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.PreferenceDefaults
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.MemoStatisticsRepository
import com.lomo.domain.repository.PreferencesRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Aggregated UI preferences consumed by multiple screens.
 */
data class AppPreferencesState(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val colorSource: ColorSource,
    val fontPreference: FontPreference,
    val customFontPath: String?,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val memoActionOrdersByScope: ImmutableMap<String, ImmutableList<String>> = persistentMapOf(),
    val inputToolbarToolOrder: ImmutableList<String>,
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
                colorSource = ColorSource.default(),
                fontPreference = FontPreference.default(),
                customFontPath = null,
                hapticFeedbackEnabled = PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED,
                showInputHints = PreferenceDefaults.SHOW_INPUT_HINTS,
                doubleTapEditEnabled = PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED,
                freeTextCopyEnabled = PreferenceDefaults.FREE_TEXT_COPY_ENABLED,
                memoActionAutoReorderEnabled = PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED,
                memoActionOrder = persistentListOf(),
                memoActionOrdersByScope = persistentMapOf(),
                inputToolbarToolOrder = persistentListOf(),
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

    fun memoActionOrderFor(scope: String): ImmutableList<String> =
        if (scope == MemoActionOrderScopes.MAIN) {
            memoActionOrder
        } else {
            memoActionOrdersByScope[scope] ?: persistentListOf()
        }
}

fun PreferencesRepository.observeAppPreferences(
    customFontStore: CustomFontStore,
): Flow<AppPreferencesState> =
    combine(
        observeBasePreferences(),
        observeSharePreferences(),
        observeShareCardPreferences(),
        observeTypographyPreferences(),
        combine(getColorSource(), observeResolvedFontPreference(customFontStore)) { color, font ->
            DisplaySourcePreferences(colorSource = color, fontResolution = font)
        },
    ) { base, share, shareCard, typography, display ->
        AppPreferencesState(
            dateFormat = base.dateFormat,
            timeFormat = base.timeFormat,
            themeMode = base.themeMode,
            colorSource = display.colorSource,
            fontPreference = display.fontResolution.preference,
            customFontPath = display.fontResolution.resolvedPath,
            hapticFeedbackEnabled = base.hapticFeedbackEnabled,
            showInputHints = base.showInputHints,
            doubleTapEditEnabled = share.doubleTapEditEnabled,
            freeTextCopyEnabled = share.freeTextCopyEnabled,
            memoActionAutoReorderEnabled = share.memoActionAutoReorderEnabled,
            memoActionOrder = share.memoActionOrder,
            memoActionOrdersByScope = share.memoActionOrdersByScope,
            inputToolbarToolOrder = share.inputToolbarToolOrder,
            quickSaveOnBackEnabled = share.quickSaveOnBackEnabled,
            scrollbarEnabled = share.scrollbarEnabled,
            shareCardShowTime = share.shareCardShowTime,
            shareCardShowBrand = shareCard.showBrand,
            shareCardSignatureText = shareCard.signatureText,
            typographyFontSizeScale = typography.fontSizeScale,
            typographyLineHeightScale = typography.lineHeightScale,
            typographyLetterSpacingScale = typography.letterSpacingScale,
            typographyParagraphSpacingScale = typography.paragraphSpacingScale,
        )
    }

private fun PreferencesRepository.observeResolvedFontPreference(
    customFontStore: CustomFontStore,
): Flow<FontResolution> =
    getFontPreference().map { preference ->
        val resolved = when (preference) {
            is FontPreference.SystemDefault -> null
            is FontPreference.UserImported -> customFontStore.resolveFontPath(preference.id)
        }
        if (preference is FontPreference.UserImported && resolved == null) {
            // behavior-contract: silent-result-ok: missing user font file → caller falls back to
            // the system default; clearing the persisted id is the responsibility of the font
            // settings page, not this read path.
            FontResolution(FontPreference.SystemDefault, null)
        } else {
            FontResolution(preference, resolved)
        }
    }

private data class FontResolution(
    val preference: FontPreference,
    val resolvedPath: String?,
)

private data class DisplaySourcePreferences(
    val colorSource: ColorSource,
    val fontResolution: FontResolution,
)

private fun PreferencesRepository.observeBasePreferences(): Flow<BasePreferences> =
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
    }

private fun PreferencesRepository.observeSharePreferences(): Flow<SharePreferences> =
    combine(
        observeMemoActionPreferences(),
        isQuickSaveOnBackEnabled(),
        isShareCardShowTimeEnabled(),
        isScrollbarEnabled(),
    ) { memoAction, quickSaveOnBackEnabled, shareCardShowTime, scrollbarEnabled ->
        SharePreferences(
            doubleTapEditEnabled = memoAction.doubleTapEditEnabled,
            freeTextCopyEnabled = memoAction.freeTextCopyEnabled,
            memoActionAutoReorderEnabled = memoAction.memoActionAutoReorderEnabled,
            memoActionOrder = memoAction.memoActionOrder,
            memoActionOrdersByScope = memoAction.memoActionOrdersByScope,
            inputToolbarToolOrder = memoAction.inputToolbarToolOrder,
            quickSaveOnBackEnabled = quickSaveOnBackEnabled,
            shareCardShowTime = shareCardShowTime,
            scrollbarEnabled = scrollbarEnabled,
        )
    }

private fun PreferencesRepository.observeMemoActionPreferences(): Flow<MemoActionPreferences> =
    combine(
        observeMemoActionToggles(),
        observeMemoActionOrderPreferences(),
    ) { toggles, orders ->
        MemoActionPreferences(
            doubleTapEditEnabled = toggles.doubleTapEditEnabled,
            freeTextCopyEnabled = toggles.freeTextCopyEnabled,
            memoActionAutoReorderEnabled = toggles.memoActionAutoReorderEnabled,
            memoActionOrder = orders.memoActionOrder,
            memoActionOrdersByScope = orders.memoActionOrdersByScope,
            inputToolbarToolOrder = orders.inputToolbarToolOrder,
        )
    }

private fun PreferencesRepository.observeMemoActionToggles(): Flow<MemoActionToggles> =
    combine(
        isDoubleTapEditEnabled(),
        isFreeTextCopyEnabled(),
        isMemoActionAutoReorderEnabled(),
    ) { doubleTapEditEnabled, freeTextCopyEnabled, memoActionAutoReorderEnabled ->
        MemoActionToggles(
            doubleTapEditEnabled = doubleTapEditEnabled,
            freeTextCopyEnabled = freeTextCopyEnabled,
            memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
        )
    }

private fun PreferencesRepository.observeMemoActionOrderPreferences(): Flow<MemoActionOrderPreferences> =
    combine(
        getMemoActionOrder(),
        getMemoActionOrdersByScope(),
        getInputToolbarToolOrder(),
    ) { memoActionOrder, memoActionOrdersByScope, inputToolbarToolOrder ->
        MemoActionOrderPreferences(
            memoActionOrder = memoActionOrder.toImmutableList(),
            memoActionOrdersByScope =
                memoActionOrdersByScope
                    .mapValues { (_, order) -> order.toImmutableList() }
                    .toImmutableMap(),
            inputToolbarToolOrder = inputToolbarToolOrder.toImmutableList(),
        )
    }

private fun PreferencesRepository.observeShareCardPreferences(): Flow<ShareCardPreferences> =
    combine(
        isShareCardShowBrandEnabled(),
        getShareCardSignatureText(),
    ) { shareCardShowBrand, shareCardSignatureText ->
        ShareCardPreferences(
            showBrand = shareCardShowBrand,
            signatureText = shareCardSignatureText,
        )
    }

private fun PreferencesRepository.observeTypographyPreferences(): Flow<TypographyPreferences> =
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

fun PreferencesRepository.appPreferencesState(
    scope: CoroutineScope,
    customFontStore: CustomFontStore,
): StateFlow<AppPreferencesState> =
    observeAppPreferences(customFontStore)
        .stateIn(scope, appWhileSubscribed(), AppPreferencesState.defaults())

fun MemoStatisticsRepository.activeDayCountState(scope: CoroutineScope): StateFlow<Int> =
    getActiveDayCount()
        .stateIn(scope, appWhileSubscribed(), 0)

private data class BasePreferences(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
)

private data class MemoActionToggles(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
)

private data class MemoActionOrderPreferences(
    val memoActionOrder: ImmutableList<String>,
    val memoActionOrdersByScope: ImmutableMap<String, ImmutableList<String>>,
    val inputToolbarToolOrder: ImmutableList<String>,
)

private data class MemoActionPreferences(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val memoActionOrdersByScope: ImmutableMap<String, ImmutableList<String>>,
    val inputToolbarToolOrder: ImmutableList<String>,
)

private data class SharePreferences(
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val memoActionOrdersByScope: ImmutableMap<String, ImmutableList<String>>,
    val inputToolbarToolOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val scrollbarEnabled: Boolean,
)

private data class ShareCardPreferences(
    val showBrand: Boolean,
    val signatureText: String,
)

private data class TypographyPreferences(
    val fontSizeScale: Float,
    val lineHeightScale: Float,
    val letterSpacingScale: Float,
    val paragraphSpacingScale: Float,
)
