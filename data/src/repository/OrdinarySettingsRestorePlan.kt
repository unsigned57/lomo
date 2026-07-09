package com.lomo.data.repository

import androidx.datastore.preferences.core.Preferences
import com.lomo.data.local.datastore.LomoOrdinarySettingsRestoreTransaction
import com.lomo.domain.model.SettingDescriptor
import com.lomo.domain.model.SettingValue

internal sealed interface OrdinarySettingsRestorePlan {
    data class Restore(
        val transaction: LomoOrdinarySettingsRestoreTransaction,
    ) : OrdinarySettingsRestorePlan

    data class Skip(
        val clearsLegacyWebDavUsername: Boolean,
    ) : OrdinarySettingsRestorePlan
}

internal fun MigrationSettingsSnapshot.toValidationReport(): MigrationSettingsValidationReport {
    val preferenceKeys = preferences.keys
    val sensitiveKeys = sensitive.keys
    val providedCatalogKeys = preferenceKeys intersect DataStoreMigrationSettingsStore.catalogAppPreferenceKeys
    val providedOrdinaryKeys = preferenceKeys - DataStoreMigrationSettingsStore.legacyDrainPreferenceKeys
    val missingCatalogKeys = DataStoreMigrationSettingsStore.catalogAppPreferenceKeys - providedCatalogKeys
    val missingOrdinaryKeys =
        DataStoreMigrationSettingsStore.supportedPreferenceKeys -
            DataStoreMigrationSettingsStore.legacyDrainPreferenceKeys -
            providedOrdinaryKeys
    val missingRequiredKeys = DataStoreMigrationSettingsStore.requiredOrdinaryPreferenceKeys - providedOrdinaryKeys
    val requiredSensitiveKeys = preferences.requiredSensitiveKeys() - sensitive.legacyDrainSensitiveKeys(preferences)
    val requiredSensitivePayload = sensitive.filterKeys { key -> key in requiredSensitiveKeys }
    val invalidRequiredKeys =
        requiredSensitivePayload
            .filterValues(String::isBlank)
            .keys
    val providedRequiredKeys =
        requiredSensitivePayload
            .filterValues(String::isNotBlank)
            .keys
    val unsupportedPreferenceKeys = preferenceKeys - DataStoreMigrationSettingsStore.supportedPreferenceKeys
    val unsupportedSensitiveKeys = sensitiveKeys - DataStoreMigrationSettingsStore.supportedSensitiveKeys

    return MigrationSettingsValidationReport(
        manifest =
            MigrationSettingsManifest(
                schemaVersion = DataStoreMigrationSettingsStore.migrationSettingsSchemaVersion,
                destructiveActions =
                    buildSet {
                        if (providedOrdinaryKeys.isNotEmpty()) {
                            add(MigrationSettingsRestoreAction.ORDINARY_SETTINGS_TRANSACTION)
                        }
                        if (sensitiveKeys.isNotEmpty() || SettingsKey.WEBDAV_USERNAME in preferenceKeys) {
                            add(MigrationSettingsRestoreAction.CREDENTIAL_IMPORT)
                        }
                        if (sensitiveKeys != DataStoreMigrationSettingsStore.supportedSensitiveKeys) {
                            add(MigrationSettingsRestoreAction.CREDENTIAL_CLEAR)
                        }
                        if (SettingsKey.WEBDAV_USERNAME in preferenceKeys) {
                            add(MigrationSettingsRestoreAction.LEGACY_WEBDAV_USERNAME_DRAIN)
                        }
                    },
                restartRequiredActions = emptySet(),
                reAuthRequiredActions =
                    if (requiredSensitiveKeys.isEmpty()) {
                        emptySet()
                    } else {
                        setOf(MigrationSettingsRestoreAction.CREDENTIAL_IMPORT)
                    },
                unsupportedPreferenceKeys = unsupportedPreferenceKeys,
                unsupportedSensitiveKeys = unsupportedSensitiveKeys,
            ),
        ordinary =
            MigrationOrdinarySettingsCoverage(
                expectedCatalogKeys = DataStoreMigrationSettingsStore.catalogAppPreferenceKeys,
                providedCatalogKeys = providedCatalogKeys,
                missingCatalogKeys = missingCatalogKeys,
                expectedOrdinaryKeys =
                    DataStoreMigrationSettingsStore.supportedPreferenceKeys -
                        DataStoreMigrationSettingsStore.legacyDrainPreferenceKeys,
                providedOrdinaryKeys = providedOrdinaryKeys,
                missingOrdinaryKeys = missingOrdinaryKeys,
                missingRequiredKeys = missingRequiredKeys,
            ),
        sensitive =
            MigrationSensitiveSettingsCoverage(
                expectedSensitiveKeys = DataStoreMigrationSettingsStore.supportedSensitiveKeys,
                providedSensitiveKeys = sensitiveKeys,
                missingSensitiveKeys = DataStoreMigrationSettingsStore.supportedSensitiveKeys - sensitiveKeys,
                requiredSensitiveKeys = requiredSensitiveKeys,
                providedRequiredKeys = providedRequiredKeys,
                missingRequiredKeys = requiredSensitiveKeys - sensitiveKeys,
                invalidRequiredKeys = invalidRequiredKeys,
            ),
    )
}

internal fun MigrationSettingsSnapshot.toOrdinaryRestorePlan(): OrdinarySettingsRestorePlan =
    preferences.toOrdinaryRestorePlan(toValidationReport().ordinary)

internal fun Map<String, String>.toOrdinaryRestorePlan(
    coverage: MigrationOrdinarySettingsCoverage,
): OrdinarySettingsRestorePlan {
    if (coverage.providedOrdinaryKeys.isEmpty()) {
        return OrdinarySettingsRestorePlan.Skip(
            clearsLegacyWebDavUsername = SettingsKey.WEBDAV_USERNAME in this,
        )
    }
    val catalogValues = catalogAppPreferenceValues()
    val booleanValues = booleanPreferenceValues()
    val intValues = intPreferenceValues()
    val stringValues = requiredStringPreferenceValues()
    val nullableStringValues = nullableStringPreferenceValues()
    return OrdinarySettingsRestorePlan.Restore(
        transaction =
            LomoOrdinarySettingsRestoreTransaction(
                catalogValues = catalogValues,
                stringValues = stringValues,
                nullableStringValues =
                    nullableStringValues +
                        (SettingsKey.WEBDAV_USERNAME.requireStringPreferenceKey() to null),
                booleanValues = booleanValues,
                intValues = intValues,
            ),
    )
}

private fun Map<String, String>.catalogAppPreferenceValues(): Map<SettingDescriptor, SettingValue> =
    mapNotNull { (key, value) ->
        DataStoreMigrationSettingsStore.appPreferenceDescriptorsByStorageKey[key]?.let { descriptor ->
            descriptor to descriptor.parseStorageValue(value)
        }
    }.toMap()

private fun Map<String, String>.booleanPreferenceValues(): Map<Preferences.Key<Boolean>, Boolean> =
    DataStoreMigrationSettingsStore.booleanPreferenceKeys.associateWithPresent(this) { key, value ->
        requireNotNull(value.toBooleanStrictOrNull()) {
            "Migration setting $key must be a boolean"
        }
    }.mapKeys { (key, _) -> key.requireBooleanPreferenceKey() }

private fun Map<String, String>.intPreferenceValues(): Map<Preferences.Key<Int>, Int> =
    DataStoreMigrationSettingsStore.intPreferenceKeys.associateWithPresent(this) { key, value ->
        requireNotNull(value.toIntOrNull()) {
            "Migration setting $key must be an integer"
        }
    }.mapKeys { (key, _) -> key.requireIntPreferenceKey() }

private fun Map<String, String>.requiredStringPreferenceValues(): Map<Preferences.Key<String>, String> =
    DataStoreMigrationSettingsStore.requiredStringPreferenceKeys.associateWithPresent(this) { _, value ->
        value
    }.mapKeys { (key, _) -> key.requireStringPreferenceKey() }

private fun Map<String, String>.nullableStringPreferenceValues(): Map<Preferences.Key<String>, String?> =
    DataStoreMigrationSettingsStore.nullablePreferenceKeys.associate { key ->
        key.requireStringPreferenceKey() to this[key]
    }

private inline fun <T> Set<String>.associateWithPresent(
    values: Map<String, String>,
    transform: (key: String, value: String) -> T,
): Map<String, T> =
    mapNotNull { key ->
        values[key]?.let { value -> key to transform(key, value) }
    }.toMap()
