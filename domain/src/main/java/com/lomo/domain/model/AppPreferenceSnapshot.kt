package com.lomo.domain.model

data class AppPreferenceSnapshot(
    val dateFormat: String,
    val timeFormat: String,
    val themeMode: ThemeMode,
    val colorSource: ColorSource,
    val fontPreference: FontPreference,
    val hapticFeedbackEnabled: Boolean,
    val showInputHints: Boolean,
    val doubleTapEditEnabled: Boolean,
    val freeTextCopyEnabled: Boolean,
    val memoActionAutoReorderEnabled: Boolean,
    val memoActionOrder: List<String>,
    val memoActionOrdersByScope: Map<String, List<String>>,
    val inputToolbarToolOrder: List<String>,
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
        fun defaults(): AppPreferenceSnapshot =
            SettingsCatalog.appPreferenceDefaults()
    }
}
