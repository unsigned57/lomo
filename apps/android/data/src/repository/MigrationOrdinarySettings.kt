package com.lomo.data.repository

import androidx.datastore.preferences.core.Preferences
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.datastore.LomoDataStoreKeys
import kotlinx.coroutines.flow.first

/**
 * Single declaration for every migrated ordinary setting: the export wire key, the typed
 * DataStore key it restores into, whether an absent value means "remove" (nullable), and how
 * the live store reads it for snapshots. Snapshot building, restore plans, and the validation
 * coverage sets all derive from these three lists — adding a migrated setting is one entry
 * here, not parallel edits across mapping `when`s and key-set tables.
 *
 * Wire keys keep the exported camelCase spelling ([SettingsKey]); store keys keep the
 * persisted snake_case name ([LomoDataStoreKeys]). The two namespaces answer different
 * contracts — backup format vs DataStore slot — so the join is declared once, here.
 */
internal object MigrationOrdinarySettings {
    data class StringSetting(
        val wireKey: String,
        val dataStoreKey: Preferences.Key<String>,
        val nullable: Boolean = false,
        val read: suspend (LomoDataStore) -> String?,
    )

    data class BooleanSetting(
        val wireKey: String,
        val dataStoreKey: Preferences.Key<Boolean>,
        val read: suspend (LomoDataStore) -> Boolean,
    )

    data class IntSetting(
        val wireKey: String,
        val dataStoreKey: Preferences.Key<Int>,
        val read: suspend (LomoDataStore) -> Int,
    )

    val strings: List<StringSetting> =
        listOf(
            StringSetting(SettingsKey.SIDEBAR_TAG_ORDER, LomoDataStoreKeys.SIDEBAR_TAG_ORDER) {
                it.sidebarTagOrder.first()
            },
            StringSetting(
                SettingsKey.LAN_SHARE_DEVICE_NAME,
                LomoDataStoreKeys.LAN_SHARE_DEVICE_NAME,
                nullable = true,
            ) {
                it.lanShareDeviceName.first()
            },
            StringSetting(SettingsKey.STORAGE_FILENAME_FORMAT, LomoDataStoreKeys.STORAGE_FILENAME_FORMAT) {
                it.storageFilenameFormat.first()
            },
            StringSetting(SettingsKey.STORAGE_TIMESTAMP_FORMAT, LomoDataStoreKeys.STORAGE_TIMESTAMP_FORMAT) {
                it.storageTimestampFormat.first()
            },
            StringSetting(SettingsKey.GIT_REMOTE_URL, LomoDataStoreKeys.GIT_REMOTE_URL, nullable = true) {
                it.gitRemoteUrl.first()
            },
            StringSetting(SettingsKey.GIT_AUTHOR_NAME, LomoDataStoreKeys.GIT_AUTHOR_NAME) {
                it.gitAuthorName.first()
            },
            StringSetting(SettingsKey.GIT_AUTHOR_EMAIL, LomoDataStoreKeys.GIT_AUTHOR_EMAIL) {
                it.gitAuthorEmail.first()
            },
            StringSetting(SettingsKey.GIT_AUTO_SYNC_INTERVAL, LomoDataStoreKeys.GIT_AUTO_SYNC_INTERVAL) {
                it.gitAutoSyncInterval.first()
            },
            StringSetting(SettingsKey.SYNC_BACKEND_TYPE, LomoDataStoreKeys.SYNC_BACKEND_TYPE) {
                it.syncBackendType.first()
            },
            StringSetting(SettingsKey.WEBDAV_PROVIDER, LomoDataStoreKeys.WEBDAV_PROVIDER) {
                it.webDavProvider.first()
            },
            StringSetting(SettingsKey.WEBDAV_BASE_URL, LomoDataStoreKeys.WEBDAV_BASE_URL, nullable = true) {
                it.webDavBaseUrl.first()
            },
            StringSetting(SettingsKey.WEBDAV_ENDPOINT_URL, LomoDataStoreKeys.WEBDAV_ENDPOINT_URL, nullable = true) {
                it.webDavEndpointUrl.first()
            },
            StringSetting(SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL, LomoDataStoreKeys.WEBDAV_AUTO_SYNC_INTERVAL) {
                it.webDavAutoSyncInterval.first()
            },
            StringSetting(SettingsKey.S3_ENDPOINT_URL, LomoDataStoreKeys.S3_ENDPOINT_URL, nullable = true) {
                it.s3EndpointUrl.first()
            },
            StringSetting(SettingsKey.S3_REGION, LomoDataStoreKeys.S3_REGION, nullable = true) {
                it.s3Region.first()
            },
            StringSetting(SettingsKey.S3_BUCKET, LomoDataStoreKeys.S3_BUCKET, nullable = true) {
                it.s3Bucket.first()
            },
            StringSetting(SettingsKey.S3_PREFIX, LomoDataStoreKeys.S3_PREFIX, nullable = true) {
                it.s3Prefix.first()
            },
            StringSetting(
                SettingsKey.S3_LOCAL_SYNC_DIRECTORY,
                LomoDataStoreKeys.S3_LOCAL_SYNC_DIRECTORY,
                nullable = true,
            ) {
                it.s3LocalSyncDirectory.first()
            },
            StringSetting(SettingsKey.S3_PATH_STYLE, LomoDataStoreKeys.S3_PATH_STYLE) {
                it.s3PathStyle.first()
            },
            StringSetting(SettingsKey.S3_ENCRYPTION_MODE, LomoDataStoreKeys.S3_ENCRYPTION_MODE) {
                it.s3EncryptionMode.first()
            },
            StringSetting(
                SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION,
                LomoDataStoreKeys.S3_RCLONE_FILENAME_ENCRYPTION,
            ) {
                it.s3RcloneFilenameEncryption.first()
            },
            StringSetting(
                SettingsKey.S3_RCLONE_FILENAME_ENCODING,
                LomoDataStoreKeys.S3_RCLONE_FILENAME_ENCODING,
            ) {
                it.s3RcloneFilenameEncoding.first()
            },
            StringSetting(SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX, LomoDataStoreKeys.S3_RCLONE_ENCRYPTED_SUFFIX) {
                it.s3RcloneEncryptedSuffix.first()
            },
            StringSetting(SettingsKey.S3_AUTO_SYNC_INTERVAL, LomoDataStoreKeys.S3_AUTO_SYNC_INTERVAL) {
                it.s3AutoSyncInterval.first()
            },
        )

    val booleans: List<BooleanSetting> =
        listOf(
            BooleanSetting(SettingsKey.CHECK_UPDATES_ON_STARTUP, LomoDataStoreKeys.CHECK_UPDATES_ON_STARTUP) {
                it.checkUpdatesOnStartup.first()
            },
            BooleanSetting(SettingsKey.APP_LOCK_ENABLED, LomoDataStoreKeys.APP_LOCK_ENABLED) {
                it.appLockEnabled.first()
            },
            BooleanSetting(SettingsKey.LAN_SHARE_ENABLED, LomoDataStoreKeys.LAN_SHARE_ENABLED) {
                it.lanShareEnabled.first()
            },
            BooleanSetting(SettingsKey.SYNC_INBOX_ENABLED, LomoDataStoreKeys.SYNC_INBOX_ENABLED) {
                it.syncInboxEnabled.first()
            },
            BooleanSetting(SettingsKey.MEMO_SNAPSHOTS_ENABLED, LomoDataStoreKeys.MEMO_SNAPSHOTS_ENABLED) {
                it.memoSnapshotsEnabled.first()
            },
            BooleanSetting(SettingsKey.GIT_AUTO_SYNC_ENABLED, LomoDataStoreKeys.GIT_AUTO_SYNC_ENABLED) {
                it.gitAutoSyncEnabled.first()
            },
            BooleanSetting(SettingsKey.GIT_SYNC_ON_REFRESH, LomoDataStoreKeys.GIT_SYNC_ON_REFRESH) {
                it.gitSyncOnRefresh.first()
            },
            BooleanSetting(SettingsKey.WEBDAV_AUTO_SYNC_ENABLED, LomoDataStoreKeys.WEBDAV_AUTO_SYNC_ENABLED) {
                it.webDavAutoSyncEnabled.first()
            },
            BooleanSetting(SettingsKey.WEBDAV_SYNC_ON_REFRESH, LomoDataStoreKeys.WEBDAV_SYNC_ON_REFRESH) {
                it.webDavSyncOnRefresh.first()
            },
            BooleanSetting(
                SettingsKey.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION,
                LomoDataStoreKeys.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION,
            ) {
                it.s3RcloneDirectoryNameEncryption.first()
            },
            BooleanSetting(
                SettingsKey.S3_RCLONE_DATA_ENCRYPTION_ENABLED,
                LomoDataStoreKeys.S3_RCLONE_DATA_ENCRYPTION_ENABLED,
            ) {
                it.s3RcloneDataEncryptionEnabled.first()
            },
            BooleanSetting(SettingsKey.S3_AUTO_SYNC_ENABLED, LomoDataStoreKeys.S3_AUTO_SYNC_ENABLED) {
                it.s3AutoSyncEnabled.first()
            },
            BooleanSetting(SettingsKey.S3_SYNC_ON_REFRESH, LomoDataStoreKeys.S3_SYNC_ON_REFRESH) {
                it.s3SyncOnRefresh.first()
            },
        )

    val ints: List<IntSetting> =
        listOf(
            IntSetting(SettingsKey.MEMO_SNAPSHOT_MAX_COUNT, LomoDataStoreKeys.MEMO_SNAPSHOT_MAX_COUNT) {
                it.memoSnapshotMaxCount.first()
            },
            IntSetting(SettingsKey.MEMO_SNAPSHOT_MAX_AGE_DAYS, LomoDataStoreKeys.MEMO_SNAPSHOT_MAX_AGE_DAYS) {
                it.memoSnapshotMaxAgeDays.first()
            },
        )

    val stringWireKeys: Set<String> = strings.mapTo(linkedSetOf()) { it.wireKey }
    val nullableStringWireKeys: Set<String> =
        strings.filter { it.nullable }.mapTo(linkedSetOf()) { it.wireKey }
    val booleanWireKeys: Set<String> = booleans.mapTo(linkedSetOf()) { it.wireKey }
    val intWireKeys: Set<String> = ints.mapTo(linkedSetOf()) { it.wireKey }
}
