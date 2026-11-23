package com.lomo.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppPreferenceSnapshot
import com.lomo.domain.model.AppPreferenceSnapshotField
import com.lomo.domain.model.CalendarHeatmapThresholds
import com.lomo.domain.model.ColorPresetId
import com.lomo.domain.model.ColorSource
import com.lomo.domain.model.ContrastLevel
import com.lomo.domain.model.FontPreference
import com.lomo.domain.model.SettingDescriptor
import com.lomo.domain.model.SettingValue
import com.lomo.domain.model.SettingsCatalog
import com.lomo.domain.model.SettingsReadModel
import com.lomo.domain.model.ThemeMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/*

 * Test Change Justification:
 * Reason category: Preference schema expansion.
 * Old behavior/assertion being replaced: the exhaustive catalog sample omitted quick-ball bindings.
 * Why old assertion is no longer correct: the catalog now includes this persisted preference.
 * Coverage preserved by: all existing snapshot/restore assertions remain, with a non-default gesture mapping.
 * Why this is not fitting the test to the implementation: the fixture extends exhaustive field coverage;
 * malformed values, atomicity and prior field expectations remain enforced.
 * Behavior Contract:
 * - Unit under test: AppPreferencesSnapshotRepositoryImpl.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: build one typed app-preferences snapshot from catalog-backed settings instead of
 *   forcing app consumers to combine primitive key flows.
 *
 * Scenarios:
 * - Given current app-wide settings are changed in DataStore, when the snapshot flow is observed,
 *   then the emitted AppPreferenceSnapshot contains the typed domain values.
 * - Given untouched settings, when the snapshot flow is observed, then defaults are supplied from
 *   the settings catalog.
 * - Given raw preferences written under catalog descriptor storage keys, when the snapshot flow is
 *   observed, then production mapping emits the same typed snapshot as the catalog field map.
 *
 * Observable outcomes:
 * - AppPreferenceSnapshot field values emitted by the repository.
 *
 * TDD proof:
 * - RED: repository emits manually-combined fields and AppPreferenceSnapshot duplicates
 *   PreferenceDefaults before the catalog owns typed snapshot construction.
 *
 * Excludes:
 * - UI mapping, custom font file resolution, migration archive transactions, and provider
 *   credential settings.
 *
 * Test Change Justification:
 * - Reason category: Preference snapshot contract expansion.
 * - Old behavior/assertion being replaced: snapshot tests asserted date, time, theme, and other
 *   existing app preferences without including calendar heatmap thresholds.
 * - Why old assertion is no longer correct: calendar heatmap thresholds are now a typed
 *   app-preference field and malformed persisted values must surface instead of falling back.
 * - Coverage preserved by: the previous snapshot fields remain asserted, with added assertions
 *   for threshold read/write, catalog-keyed mapping, defaults, and invalid persisted input.
 * - Why this is not fitting the test to the implementation: the assertions exercise the repository
 *   contract at the DataStore boundary and the catalog descriptor parser, not private mapping code.
 *
 * Test Change Justification:
 * - Reason category: product feature removal (usage-based memo menu auto-reorder).
 * - Old behavior/assertion being replaced: the snapshot round-trip and defaults asserted the three memo action fields.
 * - Why old assertion is no longer correct: the memo_action_auto_reorder_enabled, memo_action_order
 *   and memo_action_orders_by_scope settings were deleted from the settings catalog, DataStore and
 *   snapshot; the redesigned memo menu has a fixed layout.
 * - Coverage preserved by: snapshot round-trip and default assertions for every remaining field.
 * - Why this is not fitting the test to the implementation: only sample values and assertions for
 *   deleted settings were removed; the scenario and every remaining setting are unchanged.
 */
class AppPreferencesSnapshotRepositoryImplTest : DataFunSpec() {
    init {
        test("given datastore preferences when app snapshot is observed then typed values are emitted") {
            runTest {
                val dataStore = createLomoDataStore(backgroundScope)
                val repository = AppPreferencesSnapshotRepositoryImpl(dataStore)

                dataStore.updateThemeMode(ThemeMode.DARK.value)
                dataStore.updateCalendarHeatmapThresholds(
                    CalendarHeatmapThresholds.of(level1Max = 2, level2Max = 5, level3Max = 9).storageValue,
                )
                dataStore.updateColorSource(ColorSource.Preset(ColorPresetId.OCEAN).storageValue)
                dataStore.updateFontPreference(FontPreference.SystemDefault.storageValue)
                dataStore.updateHapticFeedbackEnabled(false)
                dataStore.updateShowInputHints(false)
                dataStore.updateDoubleTapEditEnabled(false)
                dataStore.updateFreeTextCopyEnabled(true)
                dataStore.updateAutoOpenInputOnForeground(true)
                dataStore.updateInputToolbarToolOrder("backfill|camera")
                dataStore.updateQuickSaveOnBackEnabled(false)
                dataStore.updateScrollbarEnabled(false)
                dataStore.updateShareCardShowTime(false)
                dataStore.updateShareCardShowBrand(false)
                dataStore.updateShareCardSignatureText("Shared via Lomo")
                dataStore.updateFontSizeScale(1.2f)
                dataStore.updateLineHeightScale(1.3f)
                dataStore.updateLetterSpacingScale(1.4f)
                dataStore.updateParagraphSpacingScale(1.5f)

                val snapshot = repository.observeAppPreferenceSnapshot().first()

                snapshot shouldBe
                    AppPreferenceSnapshot(
                        themeMode = ThemeMode.DARK,
                        calendarHeatmapThresholds = CalendarHeatmapThresholds.of(
                            level1Max = 2,
                            level2Max = 5,
                            level3Max = 9,
                        ),
                        colorSource = ColorSource.Preset(ColorPresetId.OCEAN),
                        contrastLevel = ContrastLevel.default(),
                        fontPreference = FontPreference.SystemDefault,
                        hapticFeedbackEnabled = false,
                        showInputHints = false,
                        doubleTapEditEnabled = false,
                        freeTextCopyEnabled = true,
                        autoOpenInputOnForeground = true,
                        inputToolbarToolOrder = listOf("backfill", "camera"),
                        quickSaveOnBackEnabled = false,
                        scrollbarEnabled = false,
                        shareCardShowTime = false,
                        shareCardShowBrand = false,
                        shareCardSignatureText = "Shared via Lomo",
                        typographyFontSizeScale = 1.2f,
                        typographyLineHeightScale = 1.3f,
                        typographyLetterSpacingScale = 1.4f,
                        typographyParagraphSpacingScale = 1.5f,
                    )
            }
        }

        test("given untouched datastore when app snapshot is observed then catalog defaults are emitted") {
            runTest {
                val repository = AppPreferencesSnapshotRepositoryImpl(createLomoDataStore(backgroundScope))

                val snapshot = repository.observeAppPreferenceSnapshot().first()
                val expectedDefaults = AppPreferenceSnapshot.defaults()

                snapshot.themeMode shouldBe expectedDefaults.themeMode
                snapshot.calendarHeatmapThresholds shouldBe expectedDefaults.calendarHeatmapThresholds
                snapshot.colorSource shouldBe expectedDefaults.colorSource
                snapshot.fontPreference shouldBe expectedDefaults.fontPreference
                snapshot.inputToolbarToolOrder shouldContainExactly emptyList()
                snapshot.autoOpenInputOnForeground shouldBe expectedDefaults.autoOpenInputOnForeground
                snapshot.shareCardSignatureText shouldBe expectedDefaults.shareCardSignatureText
                snapshot.typographyFontSizeScale shouldBe expectedDefaults.typographyFontSizeScale
            }
        }

        test("given raw catalog-keyed preferences when snapshot is observed then catalog field mapping is emitted") {
            runTest {
                val rawDataStore =
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        produceFile = { createBackingFile() },
                    )
                val dataStore = createLomoDataStore(rawDataStore)
                val repository = AppPreferencesSnapshotRepositoryImpl(dataStore)
                val catalogValues =
                    SettingsCatalog
                        .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                        .associate { descriptor ->
                            descriptor.snapshotField to descriptor.testValue()
                        }

                rawDataStore.edit { preferences ->
                    SettingsCatalog
                        .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                        .forEach { descriptor ->
                            when (val value = catalogValues.getValue(descriptor.snapshotField)) {
                                is SettingValue.Bool ->
                                    preferences[booleanPreferencesKey(descriptor.storageKey)] = value.value

                                is SettingValue.Decimal ->
                                    preferences[floatPreferencesKey(descriptor.storageKey)] = value.value

                                is SettingValue.Text ->
                                    preferences[stringPreferencesKey(descriptor.storageKey)] = value.value
                            }
                        }
                }

                val snapshot = repository.observeAppPreferenceSnapshot().first()

                snapshot shouldBe SettingsCatalog.appPreferenceSnapshotFrom(catalogValues)
            }
        }

        test(
            "given invalid persisted heatmap thresholds when snapshot is observed then the invalid setting is exposed",
        ) {
            runTest {
                val rawDataStore =
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        produceFile = { createBackingFile() },
                    )
                val dataStore = createLomoDataStore(rawDataStore)
                val repository = AppPreferencesSnapshotRepositoryImpl(dataStore)
                val heatmapDescriptor =
                    SettingsCatalog
                        .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                        .single { descriptor ->
                            descriptor.snapshotField == AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS
                        }

                rawDataStore.edit { preferences ->
                    preferences[stringPreferencesKey(heatmapDescriptor.storageKey)] = "1,1,6"
                }

                shouldThrow<IllegalArgumentException> {
                    repository.observeAppPreferenceSnapshot().first()
                }
            }
        }
    }

    private fun createLomoDataStore(scope: CoroutineScope): LomoDataStore {
        val realDataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { createBackingFile() },
            )
        return createLomoDataStore(realDataStore)
    }

    private fun createLomoDataStore(
        realDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    ): LomoDataStore {
        val constructor = LomoDataStore::class.java.getDeclaredConstructor(
            androidx.datastore.core.DataStore::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(realDataStore)
    }

    private fun createBackingFile() =
        Files.createTempFile("lomo-app-preferences-snapshot", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }

    private fun SettingDescriptor.testValue(): SettingValue = when (snapshotField) {
        AppPreferenceSnapshotField.THEME_MODE -> SettingValue.Text(ThemeMode.DARK.value)

        AppPreferenceSnapshotField.PURE_BLACK_DARK -> SettingValue.Bool(true)

        AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS -> SettingValue.Text("2,5,9")

        AppPreferenceSnapshotField.COLOR_SOURCE -> SettingValue.Text(
            ColorSource.Preset(ColorPresetId.OCEAN).storageValue,
        )

        AppPreferenceSnapshotField.CONTRAST_LEVEL -> SettingValue.Text(ContrastLevel.HIGH.value)

        AppPreferenceSnapshotField.FONT_PREFERENCE -> SettingValue.Text(FontPreference.SystemDefault.storageValue)

        AppPreferenceSnapshotField.HAPTIC_FEEDBACK_ENABLED -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.SHOW_INPUT_HINTS -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.DOUBLE_TAP_EDIT_ENABLED -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.FREE_TEXT_COPY_ENABLED -> SettingValue.Bool(true)

        AppPreferenceSnapshotField.AUTO_OPEN_INPUT_ON_FOREGROUND -> SettingValue.Bool(true)

        AppPreferenceSnapshotField.INPUT_TOOLBAR_TOOL_ORDER -> SettingValue.Text("backfill|camera")

        AppPreferenceSnapshotField.QUICK_SAVE_ON_BACK_ENABLED -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.SCROLLBAR_ENABLED -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.QUICK_BALL_CONFIG -> SettingValue.Text("v1|SEARCH|FILTER|LONG_TEXT|NONE|RECORD")

        AppPreferenceSnapshotField.SHARE_CARD_SHOW_TIME -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.SHARE_CARD_SHOW_BRAND -> SettingValue.Bool(false)

        AppPreferenceSnapshotField.SHARE_CARD_SIGNATURE_TEXT -> SettingValue.Text("Shared via Lomo")

        AppPreferenceSnapshotField.TYPOGRAPHY_FONT_SIZE_SCALE -> SettingValue.Decimal(1.2f)

        AppPreferenceSnapshotField.TYPOGRAPHY_LINE_HEIGHT_SCALE -> SettingValue.Decimal(1.3f)

        AppPreferenceSnapshotField.TYPOGRAPHY_LETTER_SPACING_SCALE -> SettingValue.Decimal(1.4f)

        AppPreferenceSnapshotField.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE -> SettingValue.Decimal(1.5f)
    }
}
