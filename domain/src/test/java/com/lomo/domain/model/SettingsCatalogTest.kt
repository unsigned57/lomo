package com.lomo.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SettingsCatalog.
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: declare the app-wide preference schema once, including defaults, sensitivity,
 *   export policy, facet ownership, and typed snapshot membership.
 *
 * Scenarios:
 * - Given the current app-preferences read model, when descriptors are queried for the app
 *   snapshot, then every snapshot field is represented by exactly one non-sensitive descriptor.
 * - Given display/editor/share/typography settings, when catalog metadata is inspected, then
 *   defaults and export policies come from the descriptor instead of UI fallback code.
 * - Given app-preference defaults, when the typed snapshot default is requested, then the snapshot
 *   is built from catalog descriptor defaults.
 *
 * Observable outcomes:
 * - Descriptor ids, defaults, sensitivity, facets, export policies, read-model memberships, and
 *   the typed default AppPreferenceSnapshot.
 *
 * TDD proof:
 * - RED: unresolved SettingsCatalog/AppPreferenceSnapshotField before the catalog/read-model
 *   foundation exists.
 *
 * Excludes:
 * - DataStore key implementation, migration archive serialization, and Compose rendering.
 *
 * Test Change Justification:
 * - Reason category: Preference schema expansion.
 * - Old behavior/assertion being replaced: catalog metadata tests did not include calendar heatmap
 *   thresholds as an app-preference descriptor or validate its storage parser failure mode.
 * - Why old assertion is no longer correct: the heatmap threshold value object is now part of the
 *   domain settings contract and must be represented by the catalog default and parser.
 * - Coverage preserved by: descriptor uniqueness, defaults, facets, export policy, and snapshot
 *   default coverage remain, with threshold default and invalid-storage rejection added.
 * - Why this is not fitting the test to the implementation: the checks assert the public catalog
 *   contract that import/export and repositories consume.
 */
class SettingsCatalogTest : FunSpec({
    test("given app preference snapshot when catalog is queried then every field has one descriptor") {
        val descriptors = SettingsCatalog.descriptorsFor(SettingsReadModel.APP_PREFERENCES)

        descriptors.map { it.snapshotField } shouldContainExactly AppPreferenceSnapshotField.entries.toList()
        descriptors.map { it.id }.distinct().size shouldBe descriptors.size
        descriptors.map { it.storageKey }.distinct().size shouldBe descriptors.size
        descriptors.map { it.sensitivity }.distinct() shouldContainExactly listOf(SettingsSensitivity.NON_SENSITIVE)
    }

    test("given app preference descriptors when metadata is inspected then defaults and policies are centralized") {
        val descriptorsByField =
            SettingsCatalog
                .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                .associateBy { it.snapshotField }

        descriptorsByField.getValue(AppPreferenceSnapshotField.DATE_FORMAT).defaultValue shouldBe
            SettingValue.Text(PreferenceDefaults.DATE_FORMAT)
        descriptorsByField.getValue(AppPreferenceSnapshotField.THEME_MODE).defaultValue shouldBe
            SettingValue.Text(PreferenceDefaults.THEME_MODE)
        descriptorsByField.getValue(AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS).defaultValue shouldBe
            SettingValue.Text(PreferenceDefaults.CALENDAR_HEATMAP_THRESHOLDS)
        descriptorsByField.getValue(AppPreferenceSnapshotField.HAPTIC_FEEDBACK_ENABLED).defaultValue shouldBe
            SettingValue.Bool(PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED)
        descriptorsByField.getValue(AppPreferenceSnapshotField.TYPOGRAPHY_FONT_SIZE_SCALE).defaultValue shouldBe
            SettingValue.Decimal(PreferenceDefaults.TYPOGRAPHY_FONT_SIZE_SCALE)

        descriptorsByField.values.map { it.facet }.distinct() shouldContainExactlyInAnyOrder
            listOf(
                SettingsFacet.DISPLAY,
                SettingsFacet.EDITOR,
                SettingsFacet.SHARE,
                SettingsFacet.TYPOGRAPHY,
            )
        descriptorsByField.values.map { it.exportPolicy }.distinct() shouldContainExactly
            listOf(SettingsExportPolicy.PLAIN_TEXT)
    }

    test("given catalog defaults when app preference snapshot defaults are requested then typed defaults are catalog built") {
        val expectedDefaults =
            SettingsCatalog.appPreferenceSnapshotFrom(
                SettingsCatalog
                    .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                    .associate { descriptor -> descriptor.snapshotField to descriptor.defaultValue },
            )

        AppPreferenceSnapshot.defaults() shouldBe expectedDefaults
    }

    test("given invalid heatmap thresholds when catalog descriptor parses storage then the setting is rejected") {
        val descriptor =
            SettingsCatalog
                .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                .single { descriptor ->
                    descriptor.snapshotField == AppPreferenceSnapshotField.CALENDAR_HEATMAP_THRESHOLDS
                }

        shouldThrow<IllegalArgumentException> {
            descriptor.parseStorageValue("1,1,6")
        }
    }
})
