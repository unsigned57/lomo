package com.lomo.domain.model

enum class SettingsSensitivity {
    NON_SENSITIVE,
    SENSITIVE,
}

enum class SettingsFacet {
    DISPLAY,
    EDITOR,
    SHARE,
    TYPOGRAPHY,
}

enum class SettingsExportPolicy {
    PLAIN_TEXT,
    EXCLUDED,
}

enum class SettingsReadModel {
    APP_PREFERENCES,
}

enum class AppPreferenceSnapshotField {
    DATE_FORMAT,
    TIME_FORMAT,
    THEME_MODE,
    CALENDAR_HEATMAP_THRESHOLDS,
    COLOR_SOURCE,
    FONT_PREFERENCE,
    HAPTIC_FEEDBACK_ENABLED,
    SHOW_INPUT_HINTS,
    DOUBLE_TAP_EDIT_ENABLED,
    FREE_TEXT_COPY_ENABLED,
    MEMO_ACTION_AUTO_REORDER_ENABLED,
    AUTO_OPEN_INPUT_ON_FOREGROUND,
    MEMO_ACTION_ORDER,
    MEMO_ACTION_ORDERS_BY_SCOPE,
    INPUT_TOOLBAR_TOOL_ORDER,
    QUICK_SAVE_ON_BACK_ENABLED,
    SCROLLBAR_ENABLED,
    SHARE_CARD_SHOW_TIME,
    SHARE_CARD_SHOW_BRAND,
    SHARE_CARD_SIGNATURE_TEXT,
    TYPOGRAPHY_FONT_SIZE_SCALE,
    TYPOGRAPHY_LINE_HEIGHT_SCALE,
    TYPOGRAPHY_LETTER_SPACING_SCALE,
    TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
}

sealed interface SettingValue {
    data class Text(
        val value: String,
    ) : SettingValue

    data class Bool(
        val value: Boolean,
    ) : SettingValue

    data class Decimal(
        val value: Float,
    ) : SettingValue
}

data class SettingDescriptor(
    val id: String,
    val storageKey: String,
    val defaultValue: SettingValue,
    val valueContract: SettingValueContract,
    val sensitivity: SettingsSensitivity,
    val facet: SettingsFacet,
    val exportPolicy: SettingsExportPolicy,
    val readModels: Set<SettingsReadModel>,
    val snapshotField: AppPreferenceSnapshotField,
) {
    fun parseStorageValue(value: String): SettingValue =
        valueContract.parse(storageKey = storageKey, value = value)
}

sealed interface SettingValueContract {
    fun parse(
        storageKey: String,
        value: String,
    ): SettingValue

    data object Text : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue = SettingValue.Text(value)
    }

    data object Bool : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue =
            SettingValue.Bool(
                requireNotNull(value.toBooleanStrictOrNull()) {
                    "Migration setting $storageKey must be a boolean"
                },
            )
    }

    data object Decimal : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue =
            SettingValue.Decimal(
                requireNotNull(value.toFloatOrNull()) {
                    "Migration setting $storageKey must be a float"
                },
            )
    }

    data object ThemeModeText : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue =
            SettingValue.Text(
                requireSupportedText(
                    storageKey = storageKey,
                    value = value,
                    isSupported = { ThemeMode.fromValueOrNull(it) != null },
                ),
            )
    }

    data object ColorSourceText : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue =
            SettingValue.Text(
                requireSupportedText(
                    storageKey = storageKey,
                    value = value,
                    isSupported = { ColorSource.fromStorageValueOrNull(it) != null },
                ),
            )
    }

    data object CalendarHeatmapThresholdsText : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue =
            SettingValue.Text(
                requireSupportedText(
                    storageKey = storageKey,
                    value = value,
                    isSupported = { CalendarHeatmapThresholds.parseStorageValueOrNull(it) != null },
                ),
            )
    }

    data object FontPreferenceText : SettingValueContract {
        override fun parse(
            storageKey: String,
            value: String,
        ): SettingValue =
            SettingValue.Text(
                requireSupportedText(
                    storageKey = storageKey,
                    value = value,
                    isSupported = { FontPreference.fromStorageValueOrNull(it) != null },
                ),
            )
    }
}

object SettingsCatalog {
    val descriptors: List<SettingDescriptor> =
        listOf(
            text(
                id = "display.dateFormat",
                storageKey = "date_format_only",
                defaultValue = PreferenceDefaults.DATE_FORMAT,
                facet = SettingsFacet.DISPLAY,
                snapshotField = AppPreferenceSnapshotField.DATE_FORMAT,
            ),
            text(
                id = "display.timeFormat",
                storageKey = "time_format_only",
                defaultValue = PreferenceDefaults.TIME_FORMAT,
                facet = SettingsFacet.DISPLAY,
                snapshotField = AppPreferenceSnapshotField.TIME_FORMAT,
            ),
            text(
                id = "display.themeMode",
                storageKey = "theme_mode",
                defaultValue = PreferenceDefaults.THEME_MODE,
                valueContract = SettingValueContract.ThemeModeText,
                facet = SettingsFacet.DISPLAY,
                snapshotField = AppPreferenceSnapshotField.THEME_MODE,
            ),
            text(
                id = "display.calendarHeatmapThresholds",
                storageKey = "calendar_heatmap_thresholds",
                defaultValue = PreferenceDefaults.CALENDAR_HEATMAP_THRESHOLDS,
                valueContract = SettingValueContract.CalendarHeatmapThresholdsText,
                facet = SettingsFacet.DISPLAY,
                snapshotField = AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS,
            ),
            text(
                id = "display.colorSource",
                storageKey = "color_source",
                defaultValue = PreferenceDefaults.COLOR_SOURCE,
                valueContract = SettingValueContract.ColorSourceText,
                facet = SettingsFacet.DISPLAY,
                snapshotField = AppPreferenceSnapshotField.COLOR_SOURCE,
            ),
            text(
                id = "display.fontPreference",
                storageKey = "font_preference",
                defaultValue = PreferenceDefaults.FONT_PREFERENCE,
                valueContract = SettingValueContract.FontPreferenceText,
                facet = SettingsFacet.DISPLAY,
                snapshotField = AppPreferenceSnapshotField.FONT_PREFERENCE,
            ),
            bool(
                id = "editor.hapticFeedbackEnabled",
                storageKey = "haptic_feedback_enabled",
                defaultValue = PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.HAPTIC_FEEDBACK_ENABLED,
            ),
            bool(
                id = "editor.showInputHints",
                storageKey = "show_input_hints",
                defaultValue = PreferenceDefaults.SHOW_INPUT_HINTS,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.SHOW_INPUT_HINTS,
            ),
            bool(
                id = "editor.doubleTapEditEnabled",
                storageKey = "double_tap_edit_enabled",
                defaultValue = PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.DOUBLE_TAP_EDIT_ENABLED,
            ),
            bool(
                id = "editor.freeTextCopyEnabled",
                storageKey = "free_text_copy_enabled",
                defaultValue = PreferenceDefaults.FREE_TEXT_COPY_ENABLED,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.FREE_TEXT_COPY_ENABLED,
            ),
            bool(
                id = "editor.memoActionAutoReorderEnabled",
                storageKey = "memo_action_auto_reorder_enabled",
                defaultValue = PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.MEMO_ACTION_AUTO_REORDER_ENABLED,
            ),
            bool(
                id = "editor.autoOpenInputOnForeground",
                storageKey = "auto_open_input_on_foreground",
                defaultValue = PreferenceDefaults.AUTO_OPEN_INPUT_ON_FOREGROUND,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.AUTO_OPEN_INPUT_ON_FOREGROUND,
            ),
            text(
                id = "editor.memoActionOrder",
                storageKey = "memo_action_order",
                defaultValue = PreferenceDefaults.MEMO_ACTION_ORDER,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.MEMO_ACTION_ORDER,
            ),
            text(
                id = "editor.memoActionOrdersByScope",
                storageKey = "memo_action_orders_by_scope",
                defaultValue = PreferenceDefaults.MEMO_ACTION_ORDERS_BY_SCOPE,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.MEMO_ACTION_ORDERS_BY_SCOPE,
            ),
            text(
                id = "editor.inputToolbarToolOrder",
                storageKey = "input_toolbar_tool_order",
                defaultValue = PreferenceDefaults.INPUT_TOOLBAR_TOOL_ORDER,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.INPUT_TOOLBAR_TOOL_ORDER,
            ),
            bool(
                id = "editor.quickSaveOnBackEnabled",
                storageKey = "quick_save_on_back_enabled",
                defaultValue = PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.QUICK_SAVE_ON_BACK_ENABLED,
            ),
            bool(
                id = "editor.scrollbarEnabled",
                storageKey = "scrollbar_enabled",
                defaultValue = PreferenceDefaults.SCROLLBAR_ENABLED,
                facet = SettingsFacet.EDITOR,
                snapshotField = AppPreferenceSnapshotField.SCROLLBAR_ENABLED,
            ),
            bool(
                id = "share.cardShowTime",
                storageKey = "share_card_show_time",
                defaultValue = PreferenceDefaults.SHARE_CARD_SHOW_TIME,
                facet = SettingsFacet.SHARE,
                snapshotField = AppPreferenceSnapshotField.SHARE_CARD_SHOW_TIME,
            ),
            bool(
                id = "share.cardShowBrand",
                storageKey = "share_card_show_brand",
                defaultValue = PreferenceDefaults.SHARE_CARD_SHOW_BRAND,
                facet = SettingsFacet.SHARE,
                snapshotField = AppPreferenceSnapshotField.SHARE_CARD_SHOW_BRAND,
            ),
            text(
                id = "share.cardSignatureText",
                storageKey = "share_card_signature_text",
                defaultValue = PreferenceDefaults.SHARE_CARD_SIGNATURE_TEXT,
                facet = SettingsFacet.SHARE,
                snapshotField = AppPreferenceSnapshotField.SHARE_CARD_SIGNATURE_TEXT,
            ),
            decimal(
                id = "typography.fontSizeScale",
                storageKey = "typography_font_size_scale",
                defaultValue = PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE,
                facet = SettingsFacet.TYPOGRAPHY,
                snapshotField = AppPreferenceSnapshotField.TYPOGRAPHY_FONT_SIZE_SCALE,
            ),
            decimal(
                id = "typography.lineHeightScale",
                storageKey = "typography_line_height_scale",
                defaultValue = PreferenceDefaults.TYPOGRAPHY_LINE_HEIGHT_SCALE,
                facet = SettingsFacet.TYPOGRAPHY,
                snapshotField = AppPreferenceSnapshotField.TYPOGRAPHY_LINE_HEIGHT_SCALE,
            ),
            decimal(
                id = "typography.letterSpacingScale",
                storageKey = "typography_letter_spacing_scale",
                defaultValue = PreferenceDefaults.TYPOGRAPHY_LETTER_SPACING_SCALE,
                facet = SettingsFacet.TYPOGRAPHY,
                snapshotField = AppPreferenceSnapshotField.TYPOGRAPHY_LETTER_SPACING_SCALE,
            ),
            decimal(
                id = "typography.paragraphSpacingScale",
                storageKey = "typography_paragraph_spacing_scale",
                defaultValue = PreferenceDefaults.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
                facet = SettingsFacet.TYPOGRAPHY,
                snapshotField = AppPreferenceSnapshotField.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
            ),
        )

    fun descriptorsFor(readModel: SettingsReadModel): List<SettingDescriptor> =
        descriptors.filter { descriptor -> readModel in descriptor.readModels }

    fun appPreferenceDefaults(): AppPreferenceSnapshot =
        appPreferenceSnapshotFrom(
            appPreferenceDescriptors().associate { descriptor ->
                descriptor.snapshotField to descriptor.defaultValue
            },
        )

    fun appPreferenceSnapshotFrom(valuesByField: Map<AppPreferenceSnapshotField, SettingValue>): AppPreferenceSnapshot {
        val fields = appPreferenceDescriptors().associateBy { descriptor -> descriptor.snapshotField }
        return AppPreferenceSnapshot(
            dateFormat = valuesByField.requireText(fields, AppPreferenceSnapshotField.DATE_FORMAT),
            timeFormat = valuesByField.requireText(fields, AppPreferenceSnapshotField.TIME_FORMAT),
            themeMode = ThemeMode.fromValue(valuesByField.requireText(fields, AppPreferenceSnapshotField.THEME_MODE)),
            calendarHeatmapThresholds =
                CalendarHeatmapThresholds.parseStorageValue(
                    valuesByField.requireText(fields, AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS),
                ),
            colorSource =
                ColorSource.fromStorageValue(
                    valuesByField.requireText(fields, AppPreferenceSnapshotField.COLOR_SOURCE),
                ),
            fontPreference =
                FontPreference.fromStorageValue(
                    valuesByField.requireText(fields, AppPreferenceSnapshotField.FONT_PREFERENCE),
                ),
            hapticFeedbackEnabled =
                valuesByField.requireBool(fields, AppPreferenceSnapshotField.HAPTIC_FEEDBACK_ENABLED),
            showInputHints =
                valuesByField.requireBool(fields, AppPreferenceSnapshotField.SHOW_INPUT_HINTS),
            doubleTapEditEnabled =
                valuesByField.requireBool(fields, AppPreferenceSnapshotField.DOUBLE_TAP_EDIT_ENABLED),
            freeTextCopyEnabled = valuesByField.requireBool(fields, AppPreferenceSnapshotField.FREE_TEXT_COPY_ENABLED),
            memoActionAutoReorderEnabled =
                valuesByField.requireBool(fields, AppPreferenceSnapshotField.MEMO_ACTION_AUTO_REORDER_ENABLED),
            autoOpenInputOnForeground =
                valuesByField.requireBool(fields, AppPreferenceSnapshotField.AUTO_OPEN_INPUT_ON_FOREGROUND),
            memoActionOrder =
                PreferenceValueCodecs.decodeMemoActionOrder(
                    valuesByField.requireText(fields, AppPreferenceSnapshotField.MEMO_ACTION_ORDER),
                ),
            memoActionOrdersByScope =
                PreferenceValueCodecs.withMainMemoActionScope(
                    mainOrder =
                        PreferenceValueCodecs.decodeMemoActionOrder(
                            valuesByField.requireText(fields, AppPreferenceSnapshotField.MEMO_ACTION_ORDER),
                        ),
                    scopedOrders =
                        PreferenceValueCodecs.decodeMemoActionOrdersByScope(
                            valuesByField.requireText(fields, AppPreferenceSnapshotField.MEMO_ACTION_ORDERS_BY_SCOPE),
                        ),
                ),
            inputToolbarToolOrder =
                PreferenceValueCodecs.decodeInputToolbarToolOrder(
                    valuesByField.requireText(fields, AppPreferenceSnapshotField.INPUT_TOOLBAR_TOOL_ORDER),
                ),
            quickSaveOnBackEnabled =
                valuesByField.requireBool(fields, AppPreferenceSnapshotField.QUICK_SAVE_ON_BACK_ENABLED),
            scrollbarEnabled = valuesByField.requireBool(fields, AppPreferenceSnapshotField.SCROLLBAR_ENABLED),
            shareCardShowTime = valuesByField.requireBool(fields, AppPreferenceSnapshotField.SHARE_CARD_SHOW_TIME),
            shareCardShowBrand = valuesByField.requireBool(fields, AppPreferenceSnapshotField.SHARE_CARD_SHOW_BRAND),
            shareCardSignatureText =
                valuesByField.requireText(fields, AppPreferenceSnapshotField.SHARE_CARD_SIGNATURE_TEXT),
            typographyFontSizeScale =
                valuesByField.requireDecimal(fields, AppPreferenceSnapshotField.TYPOGRAPHY_FONT_SIZE_SCALE),
            typographyLineHeightScale =
                valuesByField.requireDecimal(fields, AppPreferenceSnapshotField.TYPOGRAPHY_LINE_HEIGHT_SCALE),
            typographyLetterSpacingScale =
                valuesByField.requireDecimal(fields, AppPreferenceSnapshotField.TYPOGRAPHY_LETTER_SPACING_SCALE),
            typographyParagraphSpacingScale =
                valuesByField.requireDecimal(fields, AppPreferenceSnapshotField.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE),
        )
    }

    private fun text(
        id: String,
        storageKey: String,
        defaultValue: String,
        valueContract: SettingValueContract = SettingValueContract.Text,
        facet: SettingsFacet,
        snapshotField: AppPreferenceSnapshotField,
    ): SettingDescriptor =
        descriptor(
            id = id,
            storageKey = storageKey,
            defaultValue = SettingValue.Text(defaultValue),
            valueContract = valueContract,
            facet = facet,
            snapshotField = snapshotField,
        )

    private fun bool(
        id: String,
        storageKey: String,
        defaultValue: Boolean,
        facet: SettingsFacet,
        snapshotField: AppPreferenceSnapshotField,
    ): SettingDescriptor =
        descriptor(
            id = id,
            storageKey = storageKey,
            defaultValue = SettingValue.Bool(defaultValue),
            valueContract = SettingValueContract.Bool,
            facet = facet,
            snapshotField = snapshotField,
        )

    private fun decimal(
        id: String,
        storageKey: String,
        defaultValue: Float,
        facet: SettingsFacet,
        snapshotField: AppPreferenceSnapshotField,
    ): SettingDescriptor =
        descriptor(
            id = id,
            storageKey = storageKey,
            defaultValue = SettingValue.Decimal(defaultValue),
            valueContract = SettingValueContract.Decimal,
            facet = facet,
            snapshotField = snapshotField,
        )

    private fun descriptor(
        id: String,
        storageKey: String,
        defaultValue: SettingValue,
        valueContract: SettingValueContract,
        facet: SettingsFacet,
        snapshotField: AppPreferenceSnapshotField,
    ): SettingDescriptor =
        SettingDescriptor(
            id = id,
            storageKey = storageKey,
            defaultValue = defaultValue,
            valueContract = valueContract,
            sensitivity = SettingsSensitivity.NON_SENSITIVE,
            facet = facet,
            exportPolicy = SettingsExportPolicy.PLAIN_TEXT,
            readModels = setOf(SettingsReadModel.APP_PREFERENCES),
            snapshotField = snapshotField,
        )
}

private fun appPreferenceDescriptors(): List<SettingDescriptor> =
    SettingsCatalog.descriptorsFor(SettingsReadModel.APP_PREFERENCES)

private fun Map<AppPreferenceSnapshotField, SettingValue>.requireText(
    descriptorsByField: Map<AppPreferenceSnapshotField, SettingDescriptor>,
    field: AppPreferenceSnapshotField,
): String =
    when (val value = valueFor(descriptorsByField, field)) {
        is SettingValue.Text -> value.value
        else -> field.typeMismatch(value)
    }

private fun Map<AppPreferenceSnapshotField, SettingValue>.requireBool(
    descriptorsByField: Map<AppPreferenceSnapshotField, SettingDescriptor>,
    field: AppPreferenceSnapshotField,
): Boolean =
    when (val value = valueFor(descriptorsByField, field)) {
        is SettingValue.Bool -> value.value
        else -> field.typeMismatch(value)
    }

private fun Map<AppPreferenceSnapshotField, SettingValue>.requireDecimal(
    descriptorsByField: Map<AppPreferenceSnapshotField, SettingDescriptor>,
    field: AppPreferenceSnapshotField,
): Float =
    when (val value = valueFor(descriptorsByField, field)) {
        is SettingValue.Decimal -> value.value
        else -> field.typeMismatch(value)
    }

private fun Map<AppPreferenceSnapshotField, SettingValue>.valueFor(
    descriptorsByField: Map<AppPreferenceSnapshotField, SettingDescriptor>,
    field: AppPreferenceSnapshotField,
): SettingValue =
    this[field] ?: descriptorsByField.getValue(field).defaultValue

private fun AppPreferenceSnapshotField.typeMismatch(value: SettingValue): Nothing =
    error("Catalog value for $this has invalid type ${value::class.simpleName}")

private fun requireSupportedText(
    storageKey: String,
    value: String,
    isSupported: (String) -> Boolean,
): String {
    require(isSupported(value)) {
        "Migration setting $storageKey has unsupported value: $value"
    }
    return value
}
