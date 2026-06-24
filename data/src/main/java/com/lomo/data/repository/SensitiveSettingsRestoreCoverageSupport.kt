package com.lomo.data.repository

import com.lomo.domain.model.CredentialField
import java.util.Locale

internal fun Map<String, String>.requiredSensitiveKeys(): Set<String> =
    buildSet {
        if (providerSettingRequiresGitCredential()) {
            add(SettingsKey.GIT_TOKEN)
        }
        if (providerSettingRequiresWebDavCredential()) {
            add(SettingsKey.WEBDAV_STORED_USERNAME)
            add(SettingsKey.WEBDAV_PASSWORD)
        }
        if (providerSettingRequiresS3Credential()) {
            add(SettingsKey.S3_ACCESS_KEY_ID)
            add(SettingsKey.S3_SECRET_ACCESS_KEY)
            if (providerSettingRequiresS3EncryptionCredential()) {
                add(SettingsKey.S3_ENCRYPTION_PASSWORD)
            }
        }
    }

internal fun Map<String, String>.legacyDrainSensitiveKeys(
    preferences: Map<String, String>,
): Set<String> =
    when {
        preferences.legacyWebDavUsernameCredentialValue() != null &&
            SettingsKey.WEBDAV_STORED_USERNAME !in this ->
            setOf(SettingsKey.WEBDAV_STORED_USERNAME)
        else -> emptySet()
    }

internal fun Map<String, String>.legacyWebDavUsernameCredentialValue(): String? =
    get(SettingsKey.WEBDAV_USERNAME)
        ?.trim()
        ?.takeIf(String::isNotBlank)

internal fun CredentialField.migrationSensitiveKey(): String =
    when (this) {
        CredentialField.LAN_SHARE_PAIRING_KEY_HEX -> SettingsKey.LAN_SHARE_PAIRING_KEY_HEX
        CredentialField.GIT_TOKEN -> SettingsKey.GIT_TOKEN
        CredentialField.WEBDAV_USERNAME -> SettingsKey.WEBDAV_STORED_USERNAME
        CredentialField.WEBDAV_PASSWORD -> SettingsKey.WEBDAV_PASSWORD
        CredentialField.S3_ACCESS_KEY_ID -> SettingsKey.S3_ACCESS_KEY_ID
        CredentialField.S3_SECRET_ACCESS_KEY -> SettingsKey.S3_SECRET_ACCESS_KEY
        CredentialField.S3_SESSION_TOKEN -> SettingsKey.S3_SESSION_TOKEN
        CredentialField.S3_ENCRYPTION_PASSWORD -> SettingsKey.S3_ENCRYPTION_PASSWORD
        CredentialField.S3_ENCRYPTION_PASSWORD2 -> SettingsKey.S3_ENCRYPTION_PASSWORD2
    }

private fun Map<String, String>.providerSettingRequiresGitCredential(): Boolean =
    getBoolean(SettingsKey.GIT_SYNC_ENABLED) == true ||
        get(SettingsKey.SYNC_BACKEND_TYPE).equalsBackend("git")

private fun Map<String, String>.providerSettingRequiresWebDavCredential(): Boolean =
    getBoolean(SettingsKey.WEBDAV_SYNC_ENABLED) == true ||
        get(SettingsKey.SYNC_BACKEND_TYPE).equalsBackend("webdav")

private fun Map<String, String>.providerSettingRequiresS3Credential(): Boolean =
    getBoolean(SettingsKey.S3_SYNC_ENABLED) == true ||
        get(SettingsKey.SYNC_BACKEND_TYPE).equalsBackend("s3")

private fun Map<String, String>.providerSettingRequiresS3EncryptionCredential(): Boolean =
    get(SettingsKey.S3_ENCRYPTION_MODE).equalsBackend("rclone_crypt")

private fun String?.equalsBackend(expected: String): Boolean =
    this?.trim()?.lowercase(Locale.ROOT) == expected
