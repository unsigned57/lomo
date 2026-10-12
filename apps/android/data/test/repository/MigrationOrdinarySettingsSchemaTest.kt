package com.lomo.data.repository

import androidx.datastore.preferences.core.Preferences
import com.lomo.data.local.datastore.LomoDataStoreKeys
import com.lomo.domain.model.SettingsCatalog
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppPreferenceSnapshotField
import com.lomo.domain.model.SettingsReadModel
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MigrationOrdinarySettings schema + LomoDataStoreKeys/catalog parity.
 * - Owning layer: data
 * - Priority tier: P1
 * - Capability: every migrated ordinary setting is declared once — wire key, DataStore key,
 *   nullability — and the snapshot/restore/validation sets derive from that one table.
 *
 * Scenarios:
 * - Given the ordinary-settings schema, when wire keys are inspected, then no wire name is
 *   declared twice and the restore/validation sets decompose exactly into the schema.
 * - Given the LomoDataStoreKeys key table, when storage names are inspected, then no two
 *   entries point at the same persisted name and every catalog storage key is bound.
 *
 * Observable outcomes:
 * - Wire-key sets equal the schema decomposition; storage-key names are unique and cover
 *   every SettingsCatalog storage key.
 *
 * TDD proof:
 * - RED: MigrationOrdinarySettings does not exist before the single-source rebuild, so the
 *   derivation assertions cannot resolve.
 *
 * Excludes:
 * - Snapshot serialization and credential store writes (covered by store contract tests).
 */
class MigrationOrdinarySettingsSchemaTest : DataFunSpec() {
    init {
        test("given ordinary schema when wire keys inspected then each migrated setting is declared once") {
            val allWireKeys =
                MigrationOrdinarySettings.strings.map { it.wireKey } +
                    MigrationOrdinarySettings.booleans.map { it.wireKey } +
                    MigrationOrdinarySettings.ints.map { it.wireKey }

            allWireKeys.distinct() shouldHaveSize allWireKeys.size
            MigrationOrdinarySettings.strings.map { it.dataStoreKey.name } shouldContainExactlyInAnyOrder
                MigrationOrdinarySettings.strings.map { it.dataStoreKey }.map { it.name }
        }

        test("given schema when restore sets derived then supported keys decompose exactly") {
            val schemaWireKeys =
                MigrationOrdinarySettings.strings.mapTo(mutableSetOf()) { it.wireKey } +
                    MigrationOrdinarySettings.booleans.mapTo(mutableSetOf()) { it.wireKey } +
                    MigrationOrdinarySettings.ints.mapTo(mutableSetOf()) { it.wireKey }

            DataStoreMigrationSettingsStore.stringPreferenceKeys shouldBe
                MigrationOrdinarySettings.strings.mapTo(mutableSetOf()) { it.wireKey }
            DataStoreMigrationSettingsStore.booleanPreferenceKeys shouldBe
                MigrationOrdinarySettings.booleans.mapTo(mutableSetOf()) { it.wireKey }
            DataStoreMigrationSettingsStore.intPreferenceKeys shouldBe
                MigrationOrdinarySettings.ints.mapTo(mutableSetOf()) { it.wireKey }
            DataStoreMigrationSettingsStore.nullablePreferenceKeys shouldBe
                MigrationOrdinarySettings.strings.filter { it.nullable }.mapTo(mutableSetOf()) { it.wireKey }
            DataStoreMigrationSettingsStore.requiredStringPreferenceKeys shouldBe
                DataStoreMigrationSettingsStore.stringPreferenceKeys -
                    DataStoreMigrationSettingsStore.nullablePreferenceKeys
            DataStoreMigrationSettingsStore.supportedPreferenceKeys shouldBe
                DataStoreMigrationSettingsStore.catalogAppPreferenceKeys +
                    schemaWireKeys +
                    DataStoreMigrationSettingsStore.legacyDrainPreferenceKeys
            DataStoreMigrationSettingsStore.requiredOrdinaryPreferenceKeys shouldBe
                DataStoreMigrationSettingsStore.catalogAppPreferenceKeys +
                    DataStoreMigrationSettingsStore.requiredStringPreferenceKeys +
                    DataStoreMigrationSettingsStore.booleanPreferenceKeys +
                    DataStoreMigrationSettingsStore.intPreferenceKeys
        }

        test("given schema when nullable membership checked then every nullable key is a string setting") {
            MigrationOrdinarySettings.strings.filter { it.nullable }.forEach { entry ->
                DataStoreMigrationSettingsStore.nullablePreferenceKeys.contains(entry.wireKey) shouldBe true
            }
        }

        test("given store keys when names inspected then storage names are unique and cover the catalog") {
            val storeKeyNames = lomoDataStoreKeyNames()
            storeKeyNames.distinct() shouldHaveSize storeKeyNames.size

            val catalogStorageKeys =
                SettingsCatalog.descriptorsFor(SettingsReadModel.APP_PREFERENCES).map { it.storageKey }
            storeKeyNames.containsAll(catalogStorageKeys) shouldBe true
        }

        test("given catalog fields when storage key lookup runs then every field resolves its descriptor key") {
            AppPreferenceSnapshotField.entries.forEach { field ->
                SettingsCatalog.appPreferenceStorageKey(field) shouldBe
                    SettingsCatalog
                        .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                        .single { it.snapshotField == field }
                        .storageKey
            }
        }

        test("given catalog storage keys when store keys inspected then each resolves to a typed store key") {
            val bindings: Map<AppPreferenceSnapshotField, Preferences.Key<*>> =
                mapOf(
                    AppPreferenceSnapshotField.DATE_FORMAT to LomoDataStoreKeys.DATE_FORMAT,
                    AppPreferenceSnapshotField.TIME_FORMAT to LomoDataStoreKeys.TIME_FORMAT,
                    AppPreferenceSnapshotField.THEME_MODE to LomoDataStoreKeys.THEME_MODE,
                    AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS to
                        LomoDataStoreKeys.CALENDAR_HEATMAP_THRESHOLDS,
                    AppPreferenceSnapshotField.COLOR_SOURCE to LomoDataStoreKeys.COLOR_SOURCE,
                    AppPreferenceSnapshotField.FONT_PREFERENCE to LomoDataStoreKeys.FONT_PREFERENCE,
                    AppPreferenceSnapshotField.HAPTIC_FEEDBACK_ENABLED to
                        LomoDataStoreKeys.HAPTIC_FEEDBACK_ENABLED,
                    AppPreferenceSnapshotField.SHOW_INPUT_HINTS to LomoDataStoreKeys.SHOW_INPUT_HINTS,
                    AppPreferenceSnapshotField.DOUBLE_TAP_EDIT_ENABLED to
                        LomoDataStoreKeys.DOUBLE_TAP_EDIT_ENABLED,
                    AppPreferenceSnapshotField.FREE_TEXT_COPY_ENABLED to
                        LomoDataStoreKeys.FREE_TEXT_COPY_ENABLED,
                    AppPreferenceSnapshotField.MEMO_ACTION_AUTO_REORDER_ENABLED to
                        LomoDataStoreKeys.MEMO_ACTION_AUTO_REORDER_ENABLED,
                    AppPreferenceSnapshotField.AUTO_OPEN_INPUT_ON_FOREGROUND to
                        LomoDataStoreKeys.AUTO_OPEN_INPUT_ON_FOREGROUND,
                    AppPreferenceSnapshotField.MEMO_ACTION_ORDER to LomoDataStoreKeys.MEMO_ACTION_ORDER,
                    AppPreferenceSnapshotField.MEMO_ACTION_ORDERS_BY_SCOPE to
                        LomoDataStoreKeys.MEMO_ACTION_ORDERS_BY_SCOPE,
                    AppPreferenceSnapshotField.INPUT_TOOLBAR_TOOL_ORDER to
                        LomoDataStoreKeys.INPUT_TOOLBAR_TOOL_ORDER,
                    AppPreferenceSnapshotField.QUICK_SAVE_ON_BACK_ENABLED to
                        LomoDataStoreKeys.QUICK_SAVE_ON_BACK_ENABLED,
                    AppPreferenceSnapshotField.SCROLLBAR_ENABLED to LomoDataStoreKeys.SCROLLBAR_ENABLED,
                    AppPreferenceSnapshotField.SHARE_CARD_SHOW_TIME to LomoDataStoreKeys.SHARE_CARD_SHOW_TIME,
                    AppPreferenceSnapshotField.SHARE_CARD_SHOW_BRAND to
                        LomoDataStoreKeys.SHARE_CARD_SHOW_BRAND,
                    AppPreferenceSnapshotField.SHARE_CARD_SIGNATURE_TEXT to
                        LomoDataStoreKeys.SHARE_CARD_SIGNATURE_TEXT,
                    AppPreferenceSnapshotField.TYPOGRAPHY_FONT_SIZE_SCALE to
                        LomoDataStoreKeys.TYPOGRAPHY_FONT_SIZE_SCALE,
                    AppPreferenceSnapshotField.TYPOGRAPHY_LINE_HEIGHT_SCALE to
                        LomoDataStoreKeys.TYPOGRAPHY_LINE_HEIGHT_SCALE,
                    AppPreferenceSnapshotField.TYPOGRAPHY_LETTER_SPACING_SCALE to
                        LomoDataStoreKeys.TYPOGRAPHY_LETTER_SPACING_SCALE,
                    AppPreferenceSnapshotField.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE to
                        LomoDataStoreKeys.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
                )

            bindings.keys shouldContainExactlyInAnyOrder AppPreferenceSnapshotField.entries.toList()
            bindings.forEach { (field, key) ->
                key.name shouldBe SettingsCatalog.appPreferenceStorageKey(field)
            }
        }
    }
}

/**
 * Enumerates every `Preferences.Key` exposed by the internal `LomoDataStoreKeys` object via the
 * synthetic property getters, so the test can assert storage-name uniqueness and catalog
 * coverage without hand-maintaining a second key list.
 */
private fun lomoDataStoreKeyNames(): List<String> =
    LomoDataStoreKeys::class.java.declaredMethods
        .filter { method ->
            method.parameterCount == 0 &&
                Preferences.Key::class.java.isAssignableFrom(method.returnType)
        }
        .map { method -> (method.invoke(LomoDataStoreKeys) as Preferences.Key<*>).name }
