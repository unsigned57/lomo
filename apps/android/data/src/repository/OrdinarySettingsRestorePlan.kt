package com.lomo.data.repository

import androidx.datastore.preferences.core.Preferences
import com.lomo.data.local.datastore.LomoDataStoreKeys
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
                        (LomoDataStoreKeys.WEBDAV_USERNAME to null),
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
    buildMap {
        MigrationOrdinarySettings.booleans.forEach { setting ->
            this@booleanPreferenceValues[setting.wireKey]?.let { value ->
                put(
                    setting.dataStoreKey,
                    requireNotNull(value.toBooleanStrictOrNull()) {
                        "Migration setting ${setting.wireKey} must be a boolean"
                    },
                )
            }
        }
    }

private fun Map<String, String>.intPreferenceValues(): Map<Preferences.Key<Int>, Int> =
    buildMap {
        MigrationOrdinarySettings.ints.forEach { setting ->
            this@intPreferenceValues[setting.wireKey]?.let { value ->
                put(
                    setting.dataStoreKey,
                    requireNotNull(value.toIntOrNull()) {
                        "Migration setting ${setting.wireKey} must be an integer"
                    },
                )
            }
        }
    }

private fun Map<String, String>.requiredStringPreferenceValues(): Map<Preferences.Key<String>, String> =
    buildMap {
        MigrationOrdinarySettings.strings
            .filterNot { setting -> setting.nullable }
            .forEach { setting ->
                this@requiredStringPreferenceValues[setting.wireKey]?.let { value ->
                    put(setting.dataStoreKey, value)
                }
            }
    }

private fun Map<String, String>.nullableStringPreferenceValues(): Map<Preferences.Key<String>, String?> =
    MigrationOrdinarySettings.strings
        .filter { setting -> setting.nullable }
        .associate { setting -> setting.dataStoreKey to this[setting.wireKey] }
