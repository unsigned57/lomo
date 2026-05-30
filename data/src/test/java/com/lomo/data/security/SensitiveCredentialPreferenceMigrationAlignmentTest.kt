package com.lomo.data.security

import com.lomo.data.repository.DataStoreMigrationSettingsStore
import com.lomo.data.repository.SettingsKey
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.util.PreferenceKeys
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: SensitiveCredentialPreferencePolicy and DataStoreMigrationSettingsStore sensitive key classification
 * - Owning layer: data/security and data/repository
 * - Priority tier: P2
 * - Capability: keep DataStore-resident credential preferences on the migration sensitive channel.
 *
 * Scenarios:
 * - Given a DataStore-resident credential policy entry, when migration sensitive keys are classified, then its migration key is supported only as sensitive data.
 * - Given migration has DataStore-resident sensitive preference keys, when policy coverage is checked, then every backing preference is owned by SensitiveCredentialPreferencePolicy.
 *
 * Observable outcomes:
 * - policy key sets, migration key sets, and exact coverage between the policy and migration classification.
 *
 * TDD proof:
 * - RED: fails before the fix because the policy exposes no DataStore-resident key set and the migration store hides a manual sensitive-key list.
 *
 * Excludes:
 * - archive encryption format, secure-store credential entries, UI import flow, and full settings schema catalog.
 */
class SensitiveCredentialPreferenceMigrationAlignmentTest : DataFunSpec() {
    init {
        test("given datastore resident credential policy entries when migration sensitive keys are classified then policy entries are covered") {
            SensitiveCredentialPreferencePolicy.dataStoreResidentSensitivePreferenceKeys shouldBe
                setOf(PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX)
            DataStoreMigrationSettingsStore.dataStoreResidentSensitiveKeys shouldBe
                setOf(SettingsKey.LAN_SHARE_PAIRING_KEY_HEX)

            DataStoreMigrationSettingsStore.supportedSensitiveKeys shouldContainAll
                DataStoreMigrationSettingsStore.dataStoreResidentSensitiveKeys
            DataStoreMigrationSettingsStore.supportedPreferenceKeys
                .intersect(DataStoreMigrationSettingsStore.dataStoreResidentSensitiveKeys) shouldBe emptySet()
        }

        test("given datastore resident migration sensitive preferences when policy is checked then every key has policy coverage") {
            DataStoreMigrationSettingsStore.dataStoreResidentSensitivePreferenceKeys shouldBe
                SensitiveCredentialPreferencePolicy.dataStoreResidentSensitivePreferenceKeys
        }
    }
}
