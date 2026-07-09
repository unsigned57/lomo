package com.lomo.app.feature.preferences

import com.lomo.app.feature.common.appWhileSubscribed
import com.lomo.app.feature.common.MemoActionOrderScopes
import com.lomo.domain.model.AppPreferenceSnapshot
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.ThemeMode
import com.lomo.domain.repository.AppPreferencesSnapshotRepository
import com.lomo.domain.repository.CustomFontStore
import com.lomo.domain.repository.MemoStatisticsRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Aggregated UI preferences consumed by multiple screens.
 */
data class AppPreferencesState(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val calendarHeatmapThresholds: CalendarHeatmapThresholds,
    val colorSource: ColorSource,
    val fontPreference: FontPreference,
    val customFontPath: String?,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val autoOpenInputOnForeground: Boolean,
    val memoActionOrder: ImmutableList<String>,
    val memoActionOrdersByScope: ImmutableMap<String, ImmutableList<String>> = persistentMapOf(),
    val inputToolbarToolOrder: ImmutableList<String>,
    val quickSaveOnBackEnabled: Boolean,
    val scrollbarEnabled: Boolean,
    val shareCardShowTime: Boolean,
    val shareCardShowBrand: Boolean,
    val shareCardSignatureText: String,
    val typographyFontSizeScale: Float,
    val typographyLineHeightScale: Float,
    val typographyLetterSpacingScale: Float,
    val typographyParagraphSpacingScale: Float,
) {
    companion object {
        fun defaults(): AppPreferencesState =
            AppPreferenceSnapshot.defaults().toAppPreferencesState(FontResolution(FontPreference.default(), null))
    }

    fun memoActionOrderFor(scope: String): ImmutableList<String> =
        if (scope == MemoActionOrderScopes.MAIN) {
            memoActionOrder
        } else {
            memoActionOrdersByScope[scope] ?: persistentListOf()
        }
}

fun AppPreferencesSnapshotRepository.observeAppPreferences(
    customFontStore: CustomFontStore,
): Flow<AppPreferencesState> =
    observeAppPreferenceSnapshot().map { snapshot ->
        snapshot.toAppPreferencesState(snapshot.resolveFontPreference(customFontStore))
    }

private suspend fun AppPreferenceSnapshot.resolveFontPreference(
    customFontStore: CustomFontStore,
): FontResolution {
    val preference = fontPreference
    val resolved =
        when (preference) {
            is FontPreference.SystemDefault -> null
            is FontPreference.UserImported -> customFontStore.resolveFontPath(preference.id)
        }
    return if (preference is FontPreference.UserImported && resolved == null) {
        // behavior-contract: silent-result-ok: missing user font file -> caller falls back to
        // the system default; clearing the persisted id is the responsibility of the font
        // settings page, not this read path.
        FontResolution(FontPreference.SystemDefault, null)
    } else {
        FontResolution(preference, resolved)
    }
}

private fun AppPreferenceSnapshot.toAppPreferencesState(fontResolution: FontResolution): AppPreferencesState =
    AppPreferencesState(
        dateFormat = dateFormat,
        timeFormat = timeFormat,
        themeMode = themeMode,
        calendarHeatmapThresholds = calendarHeatmapThresholds,
        colorSource = colorSource,
        fontPreference = fontResolution.preference,
        customFontPath = fontResolution.resolvedPath,
        hapticFeedbackEnabled = hapticFeedbackEnabled,
        showInputHints = showInputHints,
        doubleTapEditEnabled = doubleTapEditEnabled,
        freeTextCopyEnabled = freeTextCopyEnabled,
        memoActionAutoReorderEnabled = memoActionAutoReorderEnabled,
        autoOpenInputOnForeground = autoOpenInputOnForeground,
        memoActionOrder = memoActionOrder.toImmutableList(),
        memoActionOrdersByScope =
            memoActionOrdersByScope
                .mapValues { (_, order) -> order.toImmutableList() }
                .toImmutableMap(),
        inputToolbarToolOrder = inputToolbarToolOrder.toImmutableList(),
        quickSaveOnBackEnabled = quickSaveOnBackEnabled,
        scrollbarEnabled = scrollbarEnabled,
        shareCardShowTime = shareCardShowTime,
        shareCardShowBrand = shareCardShowBrand,
        shareCardSignatureText = shareCardSignatureText,
        typographyFontSizeScale = typographyFontSizeScale,
        typographyLineHeightScale = typographyLineHeightScale,
        typographyLetterSpacingScale = typographyLetterSpacingScale,
        typographyParagraphSpacingScale = typographyParagraphSpacingScale,
    )

private data class FontResolution(
    val preference: FontPreference,
    val resolvedPath: String?,
)

fun AppPreferencesSnapshotRepository.appPreferencesState(
    scope: CoroutineScope,
    customFontStore: CustomFontStore,
): StateFlow<AppPreferencesState> =
    observeAppPreferences(customFontStore)
        .stateIn(scope, appWhileSubscribed(), AppPreferencesState.defaults())

fun MemoStatisticsRepository.activeDayCountState(scope: CoroutineScope): StateFlow<Int> =
    getActiveDayCount()
        .stateIn(scope, appWhileSubscribed(), 0)
