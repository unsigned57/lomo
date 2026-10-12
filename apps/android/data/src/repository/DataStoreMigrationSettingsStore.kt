package com.lomo.data.repository
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncIdentityResetPolicy
import com.lomo.domain.model.CredentialField
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.model.SettingDescriptor
import com.lomo.domain.model.SettingsCatalog
import com.lomo.domain.model.SettingsExportPolicy
import com.lomo.domain.model.SettingsReadModel
import com.lomo.domain.model.SettingsSensitivity
import com.lomo.domain.model.SettingValue
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
class DataStoreMigrationSettingsStore
constructor(
        private val dataStore: LomoDataStore,
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
        private val identityReset: SyncIdentityResetPolicy,
    ) : MigrationSettingsStore,
        MigrationSettingsRestoreValidator {
        override suspend fun snapshot(): MigrationSettingsSnapshot =
            MigrationSettingsSnapshot(
                preferences = buildPreferenceSnapshot(),
                sensitive = buildSensitiveSnapshot(),
            )
        override suspend fun validateRestore(snapshot: MigrationSettingsSnapshot): MigrationSettingsValidationReport {
            val report = snapshot.toValidationReport()
            val ordinaryPlan = snapshot.preferences.toOrdinaryRestorePlan(report.ordinary)
            if (report.hasUnsupportedKeys() ||
                ordinaryPlan is OrdinarySettingsRestorePlan.Restore && report.hasMissingRequiredCoverage()
            ) {
                throw MigrationSettingsCoverageException(report)
            }
            return report
        }
        override suspend fun restore(snapshot: MigrationSettingsSnapshot) {
            val report = validateRestore(snapshot)
            val ordinaryPlan = snapshot.preferences.toOrdinaryRestorePlan(report.ordinary)
            val rollbackSnapshot = snapshot()
            val sensitiveSettings = snapshot.sensitive.withLegacyWebDavUsername(snapshot.preferences)
            val syncIdentityMoved =
                RestoreSyncIdentityPreflight(
                    dataStore = dataStore,
                    credentialRepository = credentialRepository,
                    securitySessionPolicy = securitySessionPolicy,
                ).movesSyncIdentity(snapshot, sensitiveSettings, ordinaryPlan)
            var ordinaryRestoreAttempted = false
            try {
                // Bulk restore bypasses the per-field mutation repositories: dispose the
                // stale-identity surfaces before the first write lands, same as a settings edit.
                if (syncIdentityMoved) {
                    identityReset.resetIdentityScopedSyncState()
                }
                // Imports and omission clears are one atomic credential commit: a cancellation
                // observed inside the batch can only land before or after the whole unit, never
                // between two fields — the store therefore never leaves a mixed credential set.
                restoreSensitive(sensitive = sensitiveSettings, clearMissing = true)
                ordinaryRestoreAttempted = true
                restorePreferences(ordinaryPlan)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                rollbackRestore(
                    rollbackSnapshot = rollbackSnapshot,
                    restoreOrdinary = ordinaryRestoreAttempted,
                    originalFailure = exception,
                )
                throw exception
            }
        }
        private suspend fun rollbackRestore(
            rollbackSnapshot: MigrationSettingsSnapshot,
            restoreOrdinary: Boolean = true,
            originalFailure: Throwable,
        ) {
            if (restoreOrdinary) {
                try {
                    restorePreferences(rollbackSnapshot.toOrdinaryRestorePlan())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    originalFailure.addSuppressed(error)
                }
            }
            try {
                restoreSensitive(
                    sensitive = rollbackSnapshot.sensitive,
                    clearMissing = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                originalFailure.addSuppressed(error)
            }
        }
        private suspend fun buildPreferenceSnapshot(): Map<String, String> =
            buildMap {
                putCatalogAppPreferenceSettings(dataStore)
                MigrationOrdinarySettings.strings.forEach { setting ->
                    if (setting.nullable) {
                        putStringIfPresent(setting.wireKey, setting.read(dataStore))
                    } else {
                        putString(
                            setting.wireKey,
                            requireNotNull(setting.read(dataStore)) {
                                "Required migration setting ${setting.wireKey} produced no value"
                            },
                        )
                    }
                }
                MigrationOrdinarySettings.booleans.forEach { setting ->
                    putBoolean(setting.wireKey, setting.read(dataStore))
                }
                MigrationOrdinarySettings.ints.forEach { setting ->
                    putInt(setting.wireKey, setting.read(dataStore))
                }
                // Derived enabled views are still exported under their retired keys so older
                // builds can read the same backup; they are drained — never restored — here.
                putBoolean(SettingsKey.GIT_SYNC_ENABLED, dataStore.gitSyncEnabled.first())
                putBoolean(SettingsKey.WEBDAV_SYNC_ENABLED, dataStore.webDavSyncEnabled.first())
                putBoolean(SettingsKey.S3_SYNC_ENABLED, dataStore.s3SyncEnabled.first())
            }
        private suspend fun buildSensitiveSnapshot(): Map<String, String> {
            val sensitive = mutableMapOf<String, String>()
            drainLegacyWebDavUsername()?.let { username ->
                sensitive.putStringIfPresent(SettingsKey.WEBDAV_STORED_USERNAME, username)
            }
            sensitive.putCredentialIfPresent(SettingsKey.GIT_TOKEN, CredentialField.GIT_TOKEN)
            sensitive.putCredentialIfPresent(SettingsKey.GIT_USERNAME, CredentialField.GIT_USERNAME)
            sensitive.putCredentialIfPresent(SettingsKey.WEBDAV_STORED_USERNAME, CredentialField.WEBDAV_USERNAME)
            sensitive.putCredentialIfPresent(SettingsKey.WEBDAV_PASSWORD, CredentialField.WEBDAV_PASSWORD)
            sensitive.putCredentialIfPresent(SettingsKey.S3_ACCESS_KEY_ID, CredentialField.S3_ACCESS_KEY_ID)
            sensitive.putCredentialIfPresent(SettingsKey.S3_SECRET_ACCESS_KEY, CredentialField.S3_SECRET_ACCESS_KEY)
            sensitive.putCredentialIfPresent(SettingsKey.S3_SESSION_TOKEN, CredentialField.S3_SESSION_TOKEN)
            sensitive.putCredentialIfPresent(
                SettingsKey.S3_ENCRYPTION_PASSWORD,
                CredentialField.S3_ENCRYPTION_PASSWORD,
            )
            sensitive.putCredentialIfPresent(
                SettingsKey.S3_ENCRYPTION_PASSWORD2,
                CredentialField.S3_ENCRYPTION_PASSWORD2,
            )
            return sensitive
        }
        private fun Map<String, String>.withLegacyWebDavUsername(
            preferences: Map<String, String>,
        ): Map<String, String> {
            val legacyUsername = preferences.legacyWebDavUsernameCredentialValue()
            if (legacyUsername == null || SettingsKey.WEBDAV_STORED_USERNAME in this) {
                return this
            }
            return this + (SettingsKey.WEBDAV_STORED_USERNAME to legacyUsername)
        }
        private suspend fun restorePreferences(plan: OrdinarySettingsRestorePlan) {
            when (plan) {
                is OrdinarySettingsRestorePlan.Restore -> dataStore.restoreOrdinarySettings(plan.transaction)
                is OrdinarySettingsRestorePlan.Skip ->
                    if (plan.clearsLegacyWebDavUsername && dataStore.webDavUsername.first() != null) {
                        dataStore.updateWebDavUsername(null)
                    }
            }
        }
        private suspend fun restoreSensitive(
            sensitive: Map<String, String>,
            clearMissing: Boolean,
        ) {
            val writes = LinkedHashMap<CredentialField, String?>()
            sensitiveCredentialFields.forEach { field ->
                val key = field.migrationSensitiveKey()
                when {
                    key in sensitive -> writes[field] = sensitive.getValue(key)
                    clearMissing -> writes[field] = null
                }
            }
            credentialRepository.writeSecrets(writes)
        }
        private suspend fun MutableMap<String, String>.putCredentialIfPresent(
            key: String,
            field: CredentialField,
        ) {
            when (
                val result =
                    credentialRepository.readSecret(
                        field = field,
                        authorization = securitySessionPolicy.authorizeCredentialRead(),
                    )
            ) {
                CredentialSecretReadResult.Missing -> Unit
                is CredentialSecretReadResult.Present -> putStringIfPresent(key, result.value)
                CredentialSecretReadResult.Unreadable ->
                    error("Migration credential $key is unreadable")
                is CredentialSecretReadResult.Unauthorized ->
                    error("Migration credential $key read denied: ${result.reason}")
            }
        }
        private suspend fun drainLegacyWebDavUsername(): String? {
            val legacyUsername = dataStore.webDavUsername.first()?.run { trim().takeIf(String::isNotBlank) }
            if (legacyUsername != null) {
                credentialRepository.writeSecret(CredentialField.WEBDAV_USERNAME, legacyUsername)
                dataStore.updateWebDavUsername(null)
            }
            return legacyUsername
        }
        internal companion object {
            const val migrationSettingsSchemaVersion = 1
            val catalogAppPreferenceDescriptors =
                SettingsCatalog
                    .descriptorsFor(SettingsReadModel.APP_PREFERENCES)
                    .filter { descriptor ->
                        descriptor.sensitivity == SettingsSensitivity.NON_SENSITIVE &&
                            descriptor.exportPolicy == SettingsExportPolicy.PLAIN_TEXT
                    }
            val appPreferenceDescriptorsByStorageKey =
                catalogAppPreferenceDescriptors.associateBy { descriptor -> descriptor.storageKey }
            val catalogAppPreferenceKeys =
                appPreferenceDescriptorsByStorageKey.keys
            val credentialSensitiveKeys = emptySet<String>()
            val sensitiveCredentialFields =
                listOf(
                    CredentialField.GIT_TOKEN,
                    CredentialField.GIT_USERNAME,
                    CredentialField.WEBDAV_USERNAME,
                    CredentialField.WEBDAV_PASSWORD,
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialField.S3_SECRET_ACCESS_KEY,
                    CredentialField.S3_SESSION_TOKEN,
                    CredentialField.S3_ENCRYPTION_PASSWORD,
                    CredentialField.S3_ENCRYPTION_PASSWORD2,
                )
            // GIT_SYNC_ENABLED/WEBDAV_SYNC_ENABLED/S3_SYNC_ENABLED are intentionally absent:
            // the keys are retired in favor of SYNC_BACKEND_TYPE, so restoring them would
            // re-introduce the duplicate facts. Exports still emit the derived values for
            // older versions reading the same backup format.
            val booleanPreferenceKeys = MigrationOrdinarySettings.booleanWireKeys
            val intPreferenceKeys = MigrationOrdinarySettings.intWireKeys
            val floatPreferenceKeys =
                emptySet<String>()
            val stringPreferenceKeys = MigrationOrdinarySettings.stringWireKeys
            // Retired keys are drained without being restored: GIT/WEBDAV/S3_SYNC_ENABLED were
            // replaced by SYNC_BACKEND_TYPE, so an older backup may carry them but they must
            // not resurrect independent flag facts on this version.
            val legacyDrainPreferenceKeys =
                setOf(
                    SettingsKey.WEBDAV_USERNAME,
                    SettingsKey.GIT_SYNC_ENABLED,
                    SettingsKey.WEBDAV_SYNC_ENABLED,
                    SettingsKey.S3_SYNC_ENABLED,
                )
            val nullablePreferenceKeys = MigrationOrdinarySettings.nullableStringWireKeys
            val requiredStringPreferenceKeys =
                stringPreferenceKeys - nullablePreferenceKeys
            val requiredOrdinaryPreferenceKeys =
                catalogAppPreferenceKeys + requiredStringPreferenceKeys + booleanPreferenceKeys + intPreferenceKeys
            val supportedPreferenceKeys =
                catalogAppPreferenceKeys + stringPreferenceKeys + booleanPreferenceKeys + intPreferenceKeys +
                    legacyDrainPreferenceKeys
            val supportedSensitiveKeys =
                sensitiveCredentialFields.mapTo(mutableSetOf()) { field -> field.migrationSensitiveKey() }
        }
    }
private fun MigrationSettingsValidationReport.hasUnsupportedKeys(): Boolean =
    manifest.unsupportedPreferenceKeys.isNotEmpty() ||
        manifest.unsupportedSensitiveKeys.isNotEmpty()
private fun MigrationSettingsValidationReport.hasMissingRequiredCoverage(): Boolean =
    ordinary.missingRequiredKeys.isNotEmpty() ||
        sensitive.missingRequiredKeys.isNotEmpty() ||
        sensitive.invalidRequiredKeys.isNotEmpty()
private suspend fun MutableMap<String, String>.putCatalogAppPreferenceSettings(dataStore: LomoDataStore) {
    DataStoreMigrationSettingsStore.catalogAppPreferenceDescriptors.forEach { descriptor ->
        putString(
            key = descriptor.storageKey,
            // behavior-contract: loop-io-ok: no bulk DataStore snapshot API; each iteration is one catalog key
            value = dataStore.settingValueFlow(descriptor).first().serializeMigrationValue(),
        )
    }
}
private fun SettingValue.serializeMigrationValue(): String =
    when (this) {
        is SettingValue.Bool -> value.toString()
        is SettingValue.Decimal -> value.toString()
        is SettingValue.Text -> value
    }
