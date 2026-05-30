package com.lomo.data.repository

import com.lomo.data.git.GitCredentialStore
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.security.SensitiveCredentialPreferencePolicy
import com.lomo.data.util.PreferenceKeys
import com.lomo.data.webdav.WebDavCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreMigrationSettingsStore
    @Inject
    constructor(
        private val dataStore: LomoDataStore,
        private val gitCredentialStore: GitCredentialStore,
        private val webDavCredentialStore: WebDavCredentialStore,
        private val s3CredentialStore: S3CredentialStore,
    ) : MigrationSettingsStore,
        MigrationSettingsRestoreValidator {
        override suspend fun snapshot(): MigrationSettingsSnapshot =
            MigrationSettingsSnapshot(
                preferences = buildPreferenceSnapshot(),
                sensitive = buildSensitiveSnapshot(),
            )

        override suspend fun validateRestore(snapshot: MigrationSettingsSnapshot) {
            validateKnownKeys(
                label = "preferences",
                keys = snapshot.preferences.keys,
                supportedKeys = supportedPreferenceKeys,
            )
            validateKnownKeys(
                label = "sensitive settings",
                keys = snapshot.sensitive.keys,
                supportedKeys = supportedSensitiveKeys,
            )
            snapshot.preferences.forEach { (key, value) ->
                when (key) {
                    in booleanPreferenceKeys -> require(value.toBooleanStrictOrNull() != null) {
                        "Migration setting $key must be a boolean"
                    }
                    in intPreferenceKeys -> require(value.toIntOrNull() != null) {
                        "Migration setting $key must be an integer"
                    }
                    in floatPreferenceKeys -> require(value.toFloatOrNull() != null) {
                        "Migration setting $key must be a float"
                    }
                }
            }
        }

        override suspend fun restore(snapshot: MigrationSettingsSnapshot) {
            validateRestore(snapshot)
            val rollbackSnapshot = snapshot()
            try {
                restorePreferences(
                    preferences = snapshot.preferences,
                    clearMissing = true,
                )
                restoreSensitive(
                    sensitive = snapshot.sensitive,
                    clearMissing = true,
                )
            } catch (exception: CancellationException) {
                rollbackRestore(
                    rollbackSnapshot = rollbackSnapshot,
                    originalFailure = exception,
                )
                throw exception
            } catch (exception: Exception) {
                rollbackRestore(
                    rollbackSnapshot = rollbackSnapshot,
                    originalFailure = exception,
                )
                throw exception
            }
        }

        private suspend fun rollbackRestore(
            rollbackSnapshot: MigrationSettingsSnapshot,
            originalFailure: Throwable,
        ) {
            runCatching {
                restorePreferences(
                    preferences = rollbackSnapshot.preferences,
                    clearMissing = true,
                )
            }.onFailure(originalFailure::addSuppressed)
            runCatching {
                restoreSensitive(
                    sensitive = rollbackSnapshot.sensitive,
                    clearMissing = true,
                )
            }.onFailure(originalFailure::addSuppressed)
        }

        private suspend fun buildPreferenceSnapshot(): Map<String, String> =
            buildMap {
                putString(SettingsKey.DATE_FORMAT, dataStore.dateFormat.first())
                putString(SettingsKey.TIME_FORMAT, dataStore.timeFormat.first())
                putString(SettingsKey.THEME_MODE, dataStore.themeMode.first())
                putString(SettingsKey.COLOR_SOURCE, dataStore.colorSource.first())
                putString(SettingsKey.FONT_PREFERENCE, dataStore.fontPreference.first())
                putBoolean(SettingsKey.HAPTIC_FEEDBACK_ENABLED, dataStore.hapticFeedbackEnabled.first())
                putBoolean(SettingsKey.CHECK_UPDATES_ON_STARTUP, dataStore.checkUpdatesOnStartup.first())
                putBoolean(SettingsKey.SHOW_INPUT_HINTS, dataStore.showInputHints.first())
                putBoolean(SettingsKey.DOUBLE_TAP_EDIT_ENABLED, dataStore.doubleTapEditEnabled.first())
                putBoolean(SettingsKey.FREE_TEXT_COPY_ENABLED, dataStore.freeTextCopyEnabled.first())
                putBoolean(
                    SettingsKey.MEMO_ACTION_AUTO_REORDER_ENABLED,
                    dataStore.memoActionAutoReorderEnabled.first(),
                )
                putString(SettingsKey.MEMO_ACTION_ORDER, dataStore.memoActionOrder.first())
                putString(SettingsKey.MEMO_ACTION_ORDERS_BY_SCOPE, dataStore.memoActionOrdersByScope.first())
                putString(SettingsKey.INPUT_TOOLBAR_TOOL_ORDER, dataStore.inputToolbarToolOrder.first())
                putString(SettingsKey.SIDEBAR_TAG_ORDER, dataStore.sidebarTagOrder.first())
                putBoolean(SettingsKey.QUICK_SAVE_ON_BACK_ENABLED, dataStore.quickSaveOnBackEnabled.first())
                putBoolean(SettingsKey.SCROLLBAR_ENABLED, dataStore.scrollbarEnabled.first())
                putBoolean(SettingsKey.APP_LOCK_ENABLED, dataStore.appLockEnabled.first())
                putBoolean(SettingsKey.LAN_SHARE_ENABLED, dataStore.lanShareEnabled.first())
                putBoolean(SettingsKey.LAN_SHARE_E2E_ENABLED, dataStore.lanShareE2eEnabled.first())
                putStringIfPresent(SettingsKey.LAN_SHARE_DEVICE_NAME, dataStore.lanShareDeviceName.first())
                putBoolean(SettingsKey.SHARE_CARD_SHOW_TIME, dataStore.shareCardShowTime.first())
                putBoolean(SettingsKey.SHARE_CARD_SHOW_BRAND, dataStore.shareCardShowBrand.first())
                putString(SettingsKey.SHARE_CARD_SIGNATURE_TEXT, dataStore.shareCardSignatureText.first())
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
                putStringIfPresent(SettingsKey.WEBDAV_USERNAME, dataStore.webDavUsername.first())
                putBoolean(SettingsKey.WEBDAV_AUTO_SYNC_ENABLED, dataStore.webDavAutoSyncEnabled.first())
                putString(SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL, dataStore.webDavAutoSyncInterval.first())
                putBoolean(SettingsKey.WEBDAV_SYNC_ON_REFRESH, dataStore.webDavSyncOnRefresh.first())
                putBoolean(SettingsKey.S3_SYNC_ENABLED, dataStore.s3SyncEnabled.first())
                putStringIfPresent(SettingsKey.S3_ENDPOINT_URL, dataStore.s3EndpointUrl.first())
                putStringIfPresent(SettingsKey.S3_REGION, dataStore.s3Region.first())
                putStringIfPresent(SettingsKey.S3_BUCKET, dataStore.s3Bucket.first())
                putStringIfPresent(SettingsKey.S3_PREFIX, dataStore.s3Prefix.first())
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
                putFloat(SettingsKey.TYPOGRAPHY_FONT_SIZE_SCALE, dataStore.fontSizeScale.first())
                putFloat(SettingsKey.TYPOGRAPHY_LINE_HEIGHT_SCALE, dataStore.lineHeightScale.first())
                putFloat(SettingsKey.TYPOGRAPHY_LETTER_SPACING_SCALE, dataStore.letterSpacingScale.first())
                putFloat(SettingsKey.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE, dataStore.paragraphSpacingScale.first())
            }

        private suspend fun buildSensitiveSnapshot(): Map<String, String> =
            buildMap {
                putStringIfPresent(lanSharePairingMigrationKey, dataStore.lanSharePairingKeyHex.first())
                putStringIfPresent(SettingsKey.GIT_TOKEN, gitCredentialStore.getToken())
                putStringIfPresent(SettingsKey.WEBDAV_STORED_USERNAME, webDavCredentialStore.getUsername())
                putStringIfPresent(SettingsKey.WEBDAV_PASSWORD, webDavCredentialStore.getPassword())
                putStringIfPresent(SettingsKey.S3_ACCESS_KEY_ID, s3CredentialStore.getAccessKeyId())
                putStringIfPresent(SettingsKey.S3_SECRET_ACCESS_KEY, s3CredentialStore.getSecretAccessKey())
                putStringIfPresent(SettingsKey.S3_SESSION_TOKEN, s3CredentialStore.getSessionToken())
                putStringIfPresent(SettingsKey.S3_ENCRYPTION_PASSWORD, s3CredentialStore.getEncryptionPassword())
                putStringIfPresent(SettingsKey.S3_ENCRYPTION_PASSWORD2, s3CredentialStore.getEncryptionPassword2())
            }

        private suspend fun restorePreferences(
            preferences: Map<String, String>,
            clearMissing: Boolean = false,
        ) {
            preferences.apply {
                get(SettingsKey.DATE_FORMAT)?.let { dataStore.updateDateFormat(it) }
                get(SettingsKey.TIME_FORMAT)?.let { dataStore.updateTimeFormat(it) }
                get(SettingsKey.THEME_MODE)?.let { dataStore.updateThemeMode(it) }
                getBoolean(SettingsKey.HAPTIC_FEEDBACK_ENABLED)?.let { dataStore.updateHapticFeedbackEnabled(it) }
                getBoolean(SettingsKey.CHECK_UPDATES_ON_STARTUP)?.let { dataStore.updateCheckUpdatesOnStartup(it) }
                getBoolean(SettingsKey.SHOW_INPUT_HINTS)?.let { dataStore.updateShowInputHints(it) }
                getBoolean(SettingsKey.DOUBLE_TAP_EDIT_ENABLED)?.let { dataStore.updateDoubleTapEditEnabled(it) }
                getBoolean(SettingsKey.FREE_TEXT_COPY_ENABLED)?.let { dataStore.updateFreeTextCopyEnabled(it) }
                getBoolean(SettingsKey.MEMO_ACTION_AUTO_REORDER_ENABLED)?.let {
                    dataStore.updateMemoActionAutoReorderEnabled(it)
                }
                get(SettingsKey.MEMO_ACTION_ORDER)?.let { dataStore.updateMemoActionOrder(it) }
                get(SettingsKey.MEMO_ACTION_ORDERS_BY_SCOPE)?.let { dataStore.updateMemoActionOrdersByScope(it) }
                get(SettingsKey.INPUT_TOOLBAR_TOOL_ORDER)?.let { dataStore.updateInputToolbarToolOrder(it) }
                get(SettingsKey.SIDEBAR_TAG_ORDER)?.let { dataStore.updateSidebarTagOrder(it) }
                getBoolean(SettingsKey.QUICK_SAVE_ON_BACK_ENABLED)?.let { dataStore.updateQuickSaveOnBackEnabled(it) }
                getBoolean(SettingsKey.SCROLLBAR_ENABLED)?.let { dataStore.updateScrollbarEnabled(it) }
                getBoolean(SettingsKey.APP_LOCK_ENABLED)?.let { dataStore.updateAppLockEnabled(it) }
                getBoolean(SettingsKey.LAN_SHARE_ENABLED)?.let { dataStore.updateLanShareEnabled(it) }
                getBoolean(SettingsKey.LAN_SHARE_E2E_ENABLED)?.let { dataStore.updateLanShareE2eEnabled(it) }
                get(SettingsKey.LAN_SHARE_DEVICE_NAME)?.let { dataStore.updateLanShareDeviceName(it) }
                getBoolean(SettingsKey.SHARE_CARD_SHOW_TIME)?.let { dataStore.updateShareCardShowTime(it) }
                getBoolean(SettingsKey.SHARE_CARD_SHOW_BRAND)?.let { dataStore.updateShareCardShowBrand(it) }
                get(SettingsKey.SHARE_CARD_SIGNATURE_TEXT)?.let { dataStore.updateShareCardSignatureText(it) }
                getBoolean(SettingsKey.SYNC_INBOX_ENABLED)?.let { dataStore.updateSyncInboxEnabled(it) }
                getBoolean(SettingsKey.MEMO_SNAPSHOTS_ENABLED)?.let { dataStore.updateMemoSnapshotsEnabled(it) }
                getInt(SettingsKey.MEMO_SNAPSHOT_MAX_COUNT)?.let { dataStore.updateMemoSnapshotMaxCount(it) }
                getInt(SettingsKey.MEMO_SNAPSHOT_MAX_AGE_DAYS)?.let { dataStore.updateMemoSnapshotMaxAgeDays(it) }
                get(SettingsKey.STORAGE_FILENAME_FORMAT)?.let { dataStore.updateStorageFilenameFormat(it) }
                get(SettingsKey.STORAGE_TIMESTAMP_FORMAT)?.let { dataStore.updateStorageTimestampFormat(it) }
                getBoolean(SettingsKey.GIT_SYNC_ENABLED)?.let { dataStore.updateGitSyncEnabled(it) }
                get(SettingsKey.GIT_REMOTE_URL)?.let { dataStore.updateGitRemoteUrl(it) }
                get(SettingsKey.GIT_AUTHOR_NAME)?.let { dataStore.updateGitAuthorName(it) }
                get(SettingsKey.GIT_AUTHOR_EMAIL)?.let { dataStore.updateGitAuthorEmail(it) }
                getBoolean(SettingsKey.GIT_AUTO_SYNC_ENABLED)?.let { dataStore.updateGitAutoSyncEnabled(it) }
                get(SettingsKey.GIT_AUTO_SYNC_INTERVAL)?.let { dataStore.updateGitAutoSyncInterval(it) }
                getBoolean(SettingsKey.GIT_SYNC_ON_REFRESH)?.let { dataStore.updateGitSyncOnRefresh(it) }
                get(SettingsKey.SYNC_BACKEND_TYPE)?.let { dataStore.updateSyncBackendType(it) }
                getBoolean(SettingsKey.WEBDAV_SYNC_ENABLED)?.let { dataStore.updateWebDavSyncEnabled(it) }
                get(SettingsKey.WEBDAV_PROVIDER)?.let { dataStore.updateWebDavProvider(it) }
                get(SettingsKey.WEBDAV_BASE_URL)?.let { dataStore.updateWebDavBaseUrl(it) }
                get(SettingsKey.WEBDAV_ENDPOINT_URL)?.let { dataStore.updateWebDavEndpointUrl(it) }
                get(SettingsKey.WEBDAV_USERNAME)?.let { dataStore.updateWebDavUsername(it) }
                getBoolean(SettingsKey.WEBDAV_AUTO_SYNC_ENABLED)?.let { dataStore.updateWebDavAutoSyncEnabled(it) }
                get(SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL)?.let { dataStore.updateWebDavAutoSyncInterval(it) }
                getBoolean(SettingsKey.WEBDAV_SYNC_ON_REFRESH)?.let { dataStore.updateWebDavSyncOnRefresh(it) }
                getBoolean(SettingsKey.S3_SYNC_ENABLED)?.let { dataStore.updateS3SyncEnabled(it) }
                get(SettingsKey.S3_ENDPOINT_URL)?.let { dataStore.updateS3EndpointUrl(it) }
                get(SettingsKey.S3_REGION)?.let { dataStore.updateS3Region(it) }
                get(SettingsKey.S3_BUCKET)?.let { dataStore.updateS3Bucket(it) }
                get(SettingsKey.S3_PREFIX)?.let { dataStore.updateS3Prefix(it) }
                get(SettingsKey.S3_PATH_STYLE)?.let { dataStore.updateS3PathStyle(it) }
                get(SettingsKey.S3_ENCRYPTION_MODE)?.let { dataStore.updateS3EncryptionMode(it) }
                get(SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION)?.let {
                    dataStore.updateS3RcloneFilenameEncryption(it)
                }
                get(SettingsKey.S3_RCLONE_FILENAME_ENCODING)?.let { dataStore.updateS3RcloneFilenameEncoding(it) }
                getBoolean(SettingsKey.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION)?.let {
                    dataStore.updateS3RcloneDirectoryNameEncryption(it)
                }
                getBoolean(SettingsKey.S3_RCLONE_DATA_ENCRYPTION_ENABLED)?.let {
                    dataStore.updateS3RcloneDataEncryptionEnabled(it)
                }
                get(SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX)?.let { dataStore.updateS3RcloneEncryptedSuffix(it) }
                getBoolean(SettingsKey.S3_AUTO_SYNC_ENABLED)?.let { dataStore.updateS3AutoSyncEnabled(it) }
                get(SettingsKey.S3_AUTO_SYNC_INTERVAL)?.let { dataStore.updateS3AutoSyncInterval(it) }
                getBoolean(SettingsKey.S3_SYNC_ON_REFRESH)?.let { dataStore.updateS3SyncOnRefresh(it) }
                getFloat(SettingsKey.TYPOGRAPHY_FONT_SIZE_SCALE)?.let { dataStore.updateFontSizeScale(it) }
                getFloat(SettingsKey.TYPOGRAPHY_LINE_HEIGHT_SCALE)?.let { dataStore.updateLineHeightScale(it) }
                getFloat(SettingsKey.TYPOGRAPHY_LETTER_SPACING_SCALE)?.let {
                    dataStore.updateLetterSpacingScale(it)
                }
                getFloat(SettingsKey.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE)?.let {
                    dataStore.updateParagraphSpacingScale(it)
                }
            }
            if (clearMissing) {
                clearMissingNullablePreferences(preferences)
            }
        }

        private suspend fun restoreSensitive(
            sensitive: Map<String, String>,
            clearMissing: Boolean = false,
        ) {
            sensitive.apply {
                get(lanSharePairingMigrationKey)?.let { dataStore.updateLanSharePairingKeyHex(it) }
                get(SettingsKey.GIT_TOKEN)?.let { gitCredentialStore.setToken(it) }
                get(SettingsKey.WEBDAV_STORED_USERNAME)?.let { webDavCredentialStore.setUsername(it) }
                get(SettingsKey.WEBDAV_PASSWORD)?.let { webDavCredentialStore.setPassword(it) }
                get(SettingsKey.S3_ACCESS_KEY_ID)?.let { s3CredentialStore.setAccessKeyId(it) }
                get(SettingsKey.S3_SECRET_ACCESS_KEY)?.let { s3CredentialStore.setSecretAccessKey(it) }
                get(SettingsKey.S3_SESSION_TOKEN)?.let { s3CredentialStore.setSessionToken(it) }
                get(SettingsKey.S3_ENCRYPTION_PASSWORD)?.let { s3CredentialStore.setEncryptionPassword(it) }
                get(SettingsKey.S3_ENCRYPTION_PASSWORD2)?.let { s3CredentialStore.setEncryptionPassword2(it) }
            }
            if (clearMissing) {
                clearMissingSensitiveSettings(sensitive)
            }
        }

        private suspend fun clearMissingNullablePreferences(preferences: Map<String, String>) {
            if (SettingsKey.LAN_SHARE_DEVICE_NAME !in preferences) dataStore.updateLanShareDeviceName(null)
            if (SettingsKey.GIT_REMOTE_URL !in preferences) dataStore.updateGitRemoteUrl(null)
            if (SettingsKey.WEBDAV_BASE_URL !in preferences) dataStore.updateWebDavBaseUrl(null)
            if (SettingsKey.WEBDAV_ENDPOINT_URL !in preferences) dataStore.updateWebDavEndpointUrl(null)
            if (SettingsKey.WEBDAV_USERNAME !in preferences) dataStore.updateWebDavUsername(null)
            if (SettingsKey.S3_ENDPOINT_URL !in preferences) dataStore.updateS3EndpointUrl(null)
            if (SettingsKey.S3_REGION !in preferences) dataStore.updateS3Region(null)
            if (SettingsKey.S3_BUCKET !in preferences) dataStore.updateS3Bucket(null)
            if (SettingsKey.S3_PREFIX !in preferences) dataStore.updateS3Prefix(null)
        }

        private suspend fun clearMissingSensitiveSettings(sensitive: Map<String, String>) {
            if (lanSharePairingMigrationKey !in sensitive) dataStore.updateLanSharePairingKeyHex(null)
            if (SettingsKey.GIT_TOKEN !in sensitive) gitCredentialStore.setToken(null)
            if (SettingsKey.WEBDAV_STORED_USERNAME !in sensitive) webDavCredentialStore.setUsername(null)
            if (SettingsKey.WEBDAV_PASSWORD !in sensitive) webDavCredentialStore.setPassword(null)
            if (SettingsKey.S3_ACCESS_KEY_ID !in sensitive) s3CredentialStore.setAccessKeyId(null)
            if (SettingsKey.S3_SECRET_ACCESS_KEY !in sensitive) s3CredentialStore.setSecretAccessKey(null)
            if (SettingsKey.S3_SESSION_TOKEN !in sensitive) s3CredentialStore.setSessionToken(null)
            if (SettingsKey.S3_ENCRYPTION_PASSWORD !in sensitive) s3CredentialStore.setEncryptionPassword(null)
            if (SettingsKey.S3_ENCRYPTION_PASSWORD2 !in sensitive) s3CredentialStore.setEncryptionPassword2(null)
        }

        private fun validateKnownKeys(
            label: String,
            keys: Set<String>,
            supportedKeys: Set<String>,
        ) {
            val unknownKeys = keys - supportedKeys
            require(unknownKeys.isEmpty()) {
                "Unsupported migration $label: ${unknownKeys.sorted().joinToString()}"
            }
        }

        internal companion object {
            private val dataStoreResidentSensitiveMigrationKeyByPreferenceKey =
                mapOf(
                    PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX to SettingsKey.LAN_SHARE_PAIRING_KEY_HEX,
                )

            val dataStoreResidentSensitivePreferenceKeys: Set<String> =
                dataStoreResidentSensitiveMigrationKeyByPreferenceKey.keys

            val dataStoreResidentSensitiveKeys: Set<String> =
                SensitiveCredentialPreferencePolicy
                    .dataStoreResidentSensitivePreferenceKeys
                    .mapTo(mutableSetOf()) { preferenceKey ->
                        requireNotNull(dataStoreResidentSensitiveMigrationKeyByPreferenceKey[preferenceKey]) {
                            "No migration sensitive key for DataStore credential preference=$preferenceKey"
                        }
                    }

            val lanSharePairingMigrationKey: String =
                dataStoreResidentSensitiveMigrationKeyByPreferenceKey.getValue(PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX)

            val booleanPreferenceKeys =
                setOf(
                    SettingsKey.HAPTIC_FEEDBACK_ENABLED,
                    SettingsKey.CHECK_UPDATES_ON_STARTUP,
                    SettingsKey.SHOW_INPUT_HINTS,
                    SettingsKey.DOUBLE_TAP_EDIT_ENABLED,
                    SettingsKey.FREE_TEXT_COPY_ENABLED,
                    SettingsKey.MEMO_ACTION_AUTO_REORDER_ENABLED,
                    SettingsKey.QUICK_SAVE_ON_BACK_ENABLED,
                    SettingsKey.SCROLLBAR_ENABLED,
                    SettingsKey.APP_LOCK_ENABLED,
                    SettingsKey.LAN_SHARE_ENABLED,
                    SettingsKey.LAN_SHARE_E2E_ENABLED,
                    SettingsKey.SHARE_CARD_SHOW_TIME,
                    SettingsKey.SHARE_CARD_SHOW_BRAND,
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
                setOf(
                    SettingsKey.TYPOGRAPHY_FONT_SIZE_SCALE,
                    SettingsKey.TYPOGRAPHY_LINE_HEIGHT_SCALE,
                    SettingsKey.TYPOGRAPHY_LETTER_SPACING_SCALE,
                    SettingsKey.TYPOGRAPHY_PARAGRAPH_SPACING_SCALE,
                )

            val stringPreferenceKeys =
                setOf(
                    SettingsKey.DATE_FORMAT,
                    SettingsKey.TIME_FORMAT,
                    SettingsKey.THEME_MODE,
                    SettingsKey.MEMO_ACTION_ORDER,
                    SettingsKey.MEMO_ACTION_ORDERS_BY_SCOPE,
                    SettingsKey.INPUT_TOOLBAR_TOOL_ORDER,
                    SettingsKey.SIDEBAR_TAG_ORDER,
                    SettingsKey.LAN_SHARE_DEVICE_NAME,
                    SettingsKey.SHARE_CARD_SIGNATURE_TEXT,
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
                    SettingsKey.WEBDAV_USERNAME,
                    SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL,
                    SettingsKey.S3_ENDPOINT_URL,
                    SettingsKey.S3_REGION,
                    SettingsKey.S3_BUCKET,
                    SettingsKey.S3_PREFIX,
                    SettingsKey.S3_PATH_STYLE,
                    SettingsKey.S3_ENCRYPTION_MODE,
                    SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION,
                    SettingsKey.S3_RCLONE_FILENAME_ENCODING,
                    SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX,
                    SettingsKey.S3_AUTO_SYNC_INTERVAL,
                )

            val supportedPreferenceKeys =
                stringPreferenceKeys + booleanPreferenceKeys + intPreferenceKeys + floatPreferenceKeys

            val supportedSensitiveKeys =
                dataStoreResidentSensitiveKeys +
                    setOf(
                        SettingsKey.GIT_TOKEN,
                        SettingsKey.WEBDAV_STORED_USERNAME,
                        SettingsKey.WEBDAV_PASSWORD,
                        SettingsKey.S3_ACCESS_KEY_ID,
                        SettingsKey.S3_SECRET_ACCESS_KEY,
                        SettingsKey.S3_SESSION_TOKEN,
                        SettingsKey.S3_ENCRYPTION_PASSWORD,
                        SettingsKey.S3_ENCRYPTION_PASSWORD2,
                    )
        }
    }
