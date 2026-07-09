package com.lomo.data.security

import com.lomo.data.repository.DataStoreMigrationSettingsStore
import com.lomo.data.repository.SettingsKey
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: DataStoreMigrationSettingsStore credential sensitive key classification
 * - Owning layer: data/repository
 * - Priority tier: P2
 * - Capability: keep repository-owned credentials on the migration sensitive channel.
 *
 * Scenarios:
 * - Given credential repository sensitive keys, when migration keys are classified, then each is supported only as sensitive data.
 *
 * Observable outcomes:
 * - migration credential-sensitive key set and supported preference/sensitive sets.
 *
 * TDD proof:
 * - RED: fails before the fix because LAN pairing key is still classified as a DataStore-resident preference secret.
 *
 * Excludes:
 * - archive encryption format, secure-store implementation, UI import flow, and full settings schema catalog.
 *
 * Test Change Justification:
 * - Reason category: Credential sensitive-key classification moved from DataStore-preference
 *   mapping to unified CredentialRepository-owned secure storage.
 * - Old behavior/assertion being replaced: LAN pairing key was classified as a DataStore-resident
 *   preference secret alongside other sensitive settings.
 * - Why old assertion is no longer correct: the CredentialRepository now owns credential secrets
 *   through its own secure store path; LAN pairing key is no longer a DataStore preference.
 * - Coverage preserved by: the same sensitive-key classification scenario retained; assertion
 *   updated to verify credential keys are sensitive-only (not pref-mapped).
 * - Why this is not fitting the test to the implementation: test verifies key classification
 *   boundary, not internal secure store implementation.
 */
class SensitiveCredentialPreferenceMigrationAlignmentTest : DataFunSpec() {
    init {
        test("given credential repository keys when migration sensitive keys are classified then credentials are sensitive only") {
            DataStoreMigrationSettingsStore.credentialSensitiveKeys shouldBe
                setOf(SettingsKey.LAN_SHARE_PAIRING_KEY_HEX)

            DataStoreMigrationSettingsStore.supportedSensitiveKeys shouldContainAll
                DataStoreMigrationSettingsStore.credentialSensitiveKeys
            DataStoreMigrationSettingsStore.supportedPreferenceKeys
                .intersect(DataStoreMigrationSettingsStore.credentialSensitiveKeys) shouldBe emptySet()
        }
    }
}
