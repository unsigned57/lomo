package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreMigrationSettingsStore
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
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
            var ordinaryRestoreAttempted = false
            try {
                importSensitive(sensitiveSettings)
                ordinaryRestoreAttempted = true
                restorePreferences(ordinaryPlan)
                clearMissingSensitive(sensitiveSettings)
            } catch (exception: CancellationException) {
                rollbackRestore(
                    rollbackSnapshot = rollbackSnapshot,
                    restoreOrdinary = ordinaryRestoreAttempted,
                    originalFailure = exception,
                )
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
                // behavior-contract: silent-result-ok:
                // rollback failures are suppressed on the original restore failure.
                runCatching {
                    restorePreferences(rollbackSnapshot.toOrdinaryRestorePlan())
                }.onFailure(originalFailure::addSuppressed)
            }
            // behavior-contract: silent-result-ok: rollback failures are suppressed on the original restore failure.
            runCatching {
                restoreSensitive(
                    sensitive = rollbackSnapshot.sensitive,
                    clearMissing = true,
                )
            }.onFailure(originalFailure::addSuppressed)
        }

        private suspend fun buildPreferenceSnapshot(): Map<String, String> =
            buildMap {
                putCatalogAppPreferenceSettings(dataStore)
                putBoolean(SettingsKey.CHECK_UPDATES_ON_STARTUP, dataStore.checkUpdatesOnStartup.first())
                putString(SettingsKey.SIDEBAR_TAG_ORDER, dataStore.sidebarTagOrder.first())
                putBoolean(SettingsKey.APP_LOCK_ENABLED, dataStore.appLockEnabled.first())
                putBoolean(SettingsKey.LAN_SHARE_ENABLED, dataStore.lanShareEnabled.first())
                putBoolean(SettingsKey.LAN_SHARE_E2E_ENABLED, dataStore.lanShareE2eEnabled.first())
                putStringIfPresent(SettingsKey.LAN_SHARE_DEVICE_NAME, dataStore.lanShareDeviceName.first())
                putBoolean(SettingsKey.SYNC_INBOX_ENABLED, dataStore.syncInboxEnabled.first())
                putBoolean(SettingsKey.MEMO_SNAPSHOTS_ENABLED, dataStore.memoSnapshotsEnabled.first())
                putInt(SettingsKey.MEMO_SNAPSHOT_MAX_COUNT, dataStore.memoSnapshotMaxCount.first())
                putInt(SettingsKey.MEMO_SNAPSHOT_MAX_AGE_DAYS, dataStore.memoSnapshotMaxAgeDays.first())
                putString(SettingsKey.STORAGE_FILENAME_FORMAT, dataStore.storageFilenameFormat.first())
                putString(SettingsKey.STORAGE_TIMESTAMP_FORMAT, dataStore.storageTimestampFormat.first())
                putBoolean(SettingsKey.GIT_SYNC_ENABLED, dataStore.gitSyncEnabled.first())
                putStringIfPresent(SettingsKey.GIT_REMOTE_URL, dataStore.gitRemoteUrl.first())
                putString(SettingsKey.GIT_AUTHOR_NAME, dataStore.gitAuthorName.first())
                putString(SettingsKey.GIT_AUTHOR_EMAIL, dataStore.gitAuthorEmail.first())
                putBoolean(SettingsKey.GIT_AUTO_SYNC_ENABLED, dataStore.gitAutoSyncEnabled.first())
                putString(SettingsKey.GIT_AUTO_SYNC_INTERVAL, dataStore.gitAutoSyncInterval.first())
                putBoolean(SettingsKey.GIT_SYNC_ON_REFRESH, dataStore.gitSyncOnRefresh.first())
                putString(SettingsKey.SYNC_BACKEND_TYPE, dataStore.syncBackendType.first())
                putBoolean(SettingsKey.WEBDAV_SYNC_ENABLED, dataStore.webDavSyncEnabled.first())
                putString(SettingsKey.WEBDAV_PROVIDER, dataStore.webDavProvider.first())
                putStringIfPresent(SettingsKey.WEBDAV_BASE_URL, dataStore.webDavBaseUrl.first())
                putStringIfPresent(SettingsKey.WEBDAV_ENDPOINT_URL, dataStore.webDavEndpointUrl.first())
                putBoolean(SettingsKey.WEBDAV_AUTO_SYNC_ENABLED, dataStore.webDavAutoSyncEnabled.first())
                putString(SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL, dataStore.webDavAutoSyncInterval.first())
                putBoolean(SettingsKey.WEBDAV_SYNC_ON_REFRESH, dataStore.webDavSyncOnRefresh.first())
                putBoolean(SettingsKey.S3_SYNC_ENABLED, dataStore.s3SyncEnabled.first())
                putStringIfPresent(SettingsKey.S3_ENDPOINT_URL, dataStore.s3EndpointUrl.first())
                putStringIfPresent(SettingsKey.S3_REGION, dataStore.s3Region.first())
                putStringIfPresent(SettingsKey.S3_BUCKET, dataStore.s3Bucket.first())
                putStringIfPresent(SettingsKey.S3_PREFIX, dataStore.s3Prefix.first())
                putStringIfPresent(SettingsKey.S3_LOCAL_SYNC_DIRECTORY, dataStore.s3LocalSyncDirectory.first())
                putString(SettingsKey.S3_PATH_STYLE, dataStore.s3PathStyle.first())
                putString(SettingsKey.S3_ENCRYPTION_MODE, dataStore.s3EncryptionMode.first())
                putString(SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION, dataStore.s3RcloneFilenameEncryption.first())
                putString(SettingsKey.S3_RCLONE_FILENAME_ENCODING, dataStore.s3RcloneFilenameEncoding.first())
                putBoolean(
                    SettingsKey.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION,
                    dataStore.s3RcloneDirectoryNameEncryption.first(),
                )
                putBoolean(
                    SettingsKey.S3_RCLONE_DATA_ENCRYPTION_ENABLED,
                    dataStore.s3RcloneDataEncryptionEnabled.first(),
                )
                putString(SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX, dataStore.s3RcloneEncryptedSuffix.first())
                putBoolean(SettingsKey.S3_AUTO_SYNC_ENABLED, dataStore.s3AutoSyncEnabled.first())
                putString(SettingsKey.S3_AUTO_SYNC_INTERVAL, dataStore.s3AutoSyncInterval.first())
                putBoolean(SettingsKey.S3_SYNC_ON_REFRESH, dataStore.s3SyncOnRefresh.first())
            }

        private suspend fun buildSensitiveSnapshot(): Map<String, String> {
            val sensitive = mutableMapOf<String, String>()
            drainLegacyWebDavUsername()?.let { username ->
                sensitive.putStringIfPresent(SettingsKey.WEBDAV_STORED_USERNAME, username)
            }
            sensitive.putCredentialIfPresent(
                SettingsKey.LAN_SHARE_PAIRING_KEY_HEX,
                CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
            )
            sensitive.putCredentialIfPresent(SettingsKey.GIT_TOKEN, CredentialField.GIT_TOKEN)
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
            clearMissing: Boolean = false,
        ) {
            importSensitive(sensitive)
            if (clearMissing) {
                clearMissingSensitive(sensitive)
            }
        }

        private suspend fun importSensitive(sensitive: Map<String, String>) {
            sensitiveCredentialFields.forEach { field ->
                val key = field.migrationSensitiveKey()
                if (key in sensitive) {
                    credentialRepository.writeSecret(field, sensitive.getValue(key))
                }
            }
        }

        private suspend fun clearMissingSensitive(sensitive: Map<String, String>) {
            sensitiveCredentialFields.forEach { field ->
                if (field.migrationSensitiveKey() !in sensitive) {
                    credentialRepository.writeSecret(field, null)
                }
            }
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
            val legacyUsername = dataStore.webDavUsername.first()?.trim()?.takeIf(String::isNotBlank)
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

            val credentialSensitiveKeys =
                setOf(SettingsKey.LAN_SHARE_PAIRING_KEY_HEX)

            val sensitiveCredentialFields =
                listOf(
                    CredentialField.LAN_SHARE_PAIRING_KEY_HEX,
                    CredentialField.GIT_TOKEN,
                    CredentialField.WEBDAV_USERNAME,
                    CredentialField.WEBDAV_PASSWORD,
                    CredentialField.S3_ACCESS_KEY_ID,
                    CredentialField.S3_SECRET_ACCESS_KEY,
                    CredentialField.S3_SESSION_TOKEN,
                    CredentialField.S3_ENCRYPTION_PASSWORD,
                    CredentialField.S3_ENCRYPTION_PASSWORD2,
                )

            val booleanPreferenceKeys =
                setOf(
                    SettingsKey.CHECK_UPDATES_ON_STARTUP,
                    SettingsKey.APP_LOCK_ENABLED,
                    SettingsKey.LAN_SHARE_ENABLED,
                    SettingsKey.LAN_SHARE_E2E_ENABLED,
                    SettingsKey.SYNC_INBOX_ENABLED,
                    SettingsKey.MEMO_SNAPSHOTS_ENABLED,
                    SettingsKey.GIT_SYNC_ENABLED,
                    SettingsKey.GIT_AUTO_SYNC_ENABLED,
                    SettingsKey.GIT_SYNC_ON_REFRESH,
                    SettingsKey.WEBDAV_SYNC_ENABLED,
                    SettingsKey.WEBDAV_AUTO_SYNC_ENABLED,
                    SettingsKey.WEBDAV_SYNC_ON_REFRESH,
                    SettingsKey.S3_SYNC_ENABLED,
                    SettingsKey.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION,
                    SettingsKey.S3_RCLONE_DATA_ENCRYPTION_ENABLED,
                    SettingsKey.S3_AUTO_SYNC_ENABLED,
                    SettingsKey.S3_SYNC_ON_REFRESH,
                )

            val intPreferenceKeys =
                setOf(
                    SettingsKey.MEMO_SNAPSHOT_MAX_COUNT,
                    SettingsKey.MEMO_SNAPSHOT_MAX_AGE_DAYS,
                )

            val floatPreferenceKeys =
                emptySet<String>()

            val stringPreferenceKeys =
                setOf(
                    SettingsKey.SIDEBAR_TAG_ORDER,
                    SettingsKey.LAN_SHARE_DEVICE_NAME,
                    SettingsKey.STORAGE_FILENAME_FORMAT,
                    SettingsKey.STORAGE_TIMESTAMP_FORMAT,
                    SettingsKey.GIT_REMOTE_URL,
                    SettingsKey.GIT_AUTHOR_NAME,
                    SettingsKey.GIT_AUTHOR_EMAIL,
                    SettingsKey.GIT_AUTO_SYNC_INTERVAL,
                    SettingsKey.SYNC_BACKEND_TYPE,
                    SettingsKey.WEBDAV_PROVIDER,
                    SettingsKey.WEBDAV_BASE_URL,
                    SettingsKey.WEBDAV_ENDPOINT_URL,
                    SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL,
                    SettingsKey.S3_ENDPOINT_URL,
                    SettingsKey.S3_REGION,
                    SettingsKey.S3_BUCKET,
                    SettingsKey.S3_PREFIX,
                    SettingsKey.S3_LOCAL_SYNC_DIRECTORY,
                    SettingsKey.S3_PATH_STYLE,
                    SettingsKey.S3_ENCRYPTION_MODE,
                    SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION,
                    SettingsKey.S3_RCLONE_FILENAME_ENCODING,
                    SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX,
                    SettingsKey.S3_AUTO_SYNC_INTERVAL,
                )

            val legacyDrainPreferenceKeys =
                setOf(SettingsKey.WEBDAV_USERNAME)

            val nullablePreferenceKeys =
                setOf(
                    SettingsKey.LAN_SHARE_DEVICE_NAME,
                    SettingsKey.GIT_REMOTE_URL,
                    SettingsKey.WEBDAV_BASE_URL,
                    SettingsKey.WEBDAV_ENDPOINT_URL,
                    SettingsKey.S3_ENDPOINT_URL,
                    SettingsKey.S3_REGION,
                    SettingsKey.S3_BUCKET,
                    SettingsKey.S3_PREFIX,
                    SettingsKey.S3_LOCAL_SYNC_DIRECTORY,
                )

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
