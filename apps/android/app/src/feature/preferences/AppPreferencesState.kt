package com.lomo.app.feature.preferences

import androidx.compose.ui.text.font.FontFamily
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
/** Whether the persisted custom-font selection currently resolves to usable font bytes. */
enum class CustomFontStatus {
    /** The user picked the system font. */
    NONE_SELECTED,

    /** The selected custom font resolves and is loaded. */
    READY,

    /** A custom font is selected but its file is missing or unusable — explicit problem state. */
    MISSING,
}

data class AppPreferencesState(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val calendarHeatmapThresholds: CalendarHeatmapThresholds,
    val colorSource: ColorSource,
    val fontPreference: FontPreference,
    val customFontPath: String?,
    val customFontFamily: FontFamily,
    val customFontStatus: CustomFontStatus,
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
            AppPreferenceSnapshot
                .defaults()
                .toAppPreferencesState(FontResolution(FontPreference.default(), null, null))
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
    customFontHost: CustomFontHost,
): Flow<AppPreferencesState> =
    observeAppPreferenceSnapshot().map { snapshot ->
        snapshot.toAppPreferencesState(snapshot.resolveFontPreference(customFontStore, customFontHost))
    }

private suspend fun AppPreferenceSnapshot.resolveFontPreference(
    customFontStore: CustomFontStore,
    customFontHost: CustomFontHost,
): FontResolution {
    val preference = fontPreference
    if (preference !is FontPreference.UserImported) {
        return FontResolution(preference, null, null)
    }
    val resolved = customFontStore.resolveFontPath(preference.id)
    if (resolved == null) {
        // The persisted selection stays the fact; the missing file is an explicit status, not a
        // silently rewritten preference. The theme still falls back to the system family.
        return FontResolution(preference, null, FontFamily.SansSerif)
    }
    return FontResolution(preference, resolved, customFontHost.familyFor(preference.id))
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
        customFontFamily = fontResolution.family ?: FontFamily.SansSerif,
        customFontStatus =
            when {
                fontResolution.preference !is FontPreference.UserImported -> CustomFontStatus.NONE_SELECTED
                fontResolution.resolvedPath == null -> CustomFontStatus.MISSING
                else -> CustomFontStatus.READY
            },
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
    /** `null` for system selection or an unresolvable file; the caller reads [CustomFontStatus]. */
    val family: FontFamily?,
)

fun AppPreferencesSnapshotRepository.appPreferencesState(
    scope: CoroutineScope,
    customFontStore: CustomFontStore,
    customFontHost: CustomFontHost,
): StateFlow<AppPreferencesState> =
    observeAppPreferences(customFontStore, customFontHost)
        .stateIn(scope, appWhileSubscribed(), AppPreferencesState.defaults())

fun MemoStatisticsRepository.activeDayCountState(scope: CoroutineScope): StateFlow<Int> =
    getActiveDayCount()
        .stateIn(scope, appWhileSubscribed(), 0)
