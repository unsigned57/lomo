package com.lomo.data.repository

import androidx.datastore.preferences.core.Preferences
import com.lomo.data.local.datastore.LomoDataStoreKeys

internal fun String.requireStringPreferenceKey(): Preferences.Key<String> =
    when (this) {
        SettingsKey.SIDEBAR_TAG_ORDER -> LomoDataStoreKeys.SIDEBAR_TAG_ORDER
        SettingsKey.LAN_SHARE_DEVICE_NAME -> LomoDataStoreKeys.LAN_SHARE_DEVICE_NAME
        SettingsKey.STORAGE_FILENAME_FORMAT -> LomoDataStoreKeys.STORAGE_FILENAME_FORMAT
        SettingsKey.STORAGE_TIMESTAMP_FORMAT -> LomoDataStoreKeys.STORAGE_TIMESTAMP_FORMAT
        SettingsKey.GIT_REMOTE_URL -> LomoDataStoreKeys.GIT_REMOTE_URL
        SettingsKey.GIT_AUTHOR_NAME -> LomoDataStoreKeys.GIT_AUTHOR_NAME
        SettingsKey.GIT_AUTHOR_EMAIL -> LomoDataStoreKeys.GIT_AUTHOR_EMAIL
        SettingsKey.GIT_AUTO_SYNC_INTERVAL -> LomoDataStoreKeys.GIT_AUTO_SYNC_INTERVAL
        SettingsKey.SYNC_BACKEND_TYPE -> LomoDataStoreKeys.SYNC_BACKEND_TYPE
        SettingsKey.WEBDAV_PROVIDER -> LomoDataStoreKeys.WEBDAV_PROVIDER
        SettingsKey.WEBDAV_BASE_URL -> LomoDataStoreKeys.WEBDAV_BASE_URL
        SettingsKey.WEBDAV_ENDPOINT_URL -> LomoDataStoreKeys.WEBDAV_ENDPOINT_URL
        SettingsKey.WEBDAV_USERNAME -> LomoDataStoreKeys.WEBDAV_USERNAME
        SettingsKey.WEBDAV_AUTO_SYNC_INTERVAL -> LomoDataStoreKeys.WEBDAV_AUTO_SYNC_INTERVAL
        SettingsKey.S3_ENDPOINT_URL -> LomoDataStoreKeys.S3_ENDPOINT_URL
        SettingsKey.S3_REGION -> LomoDataStoreKeys.S3_REGION
        SettingsKey.S3_BUCKET -> LomoDataStoreKeys.S3_BUCKET
        SettingsKey.S3_PREFIX -> LomoDataStoreKeys.S3_PREFIX
        SettingsKey.S3_LOCAL_SYNC_DIRECTORY -> LomoDataStoreKeys.S3_LOCAL_SYNC_DIRECTORY
        SettingsKey.S3_PATH_STYLE -> LomoDataStoreKeys.S3_PATH_STYLE
        SettingsKey.S3_ENCRYPTION_MODE -> LomoDataStoreKeys.S3_ENCRYPTION_MODE
        SettingsKey.S3_RCLONE_FILENAME_ENCRYPTION -> LomoDataStoreKeys.S3_RCLONE_FILENAME_ENCRYPTION
        SettingsKey.S3_RCLONE_FILENAME_ENCODING -> LomoDataStoreKeys.S3_RCLONE_FILENAME_ENCODING
        SettingsKey.S3_RCLONE_ENCRYPTED_SUFFIX -> LomoDataStoreKeys.S3_RCLONE_ENCRYPTED_SUFFIX
        SettingsKey.S3_AUTO_SYNC_INTERVAL -> LomoDataStoreKeys.S3_AUTO_SYNC_INTERVAL
        else -> error("Migration setting $this is not a string preference")
    }

internal fun String.requireBooleanPreferenceKey(): Preferences.Key<Boolean> =
    when (this) {
        SettingsKey.CHECK_UPDATES_ON_STARTUP -> LomoDataStoreKeys.CHECK_UPDATES_ON_STARTUP
        SettingsKey.APP_LOCK_ENABLED -> LomoDataStoreKeys.APP_LOCK_ENABLED
        SettingsKey.LAN_SHARE_ENABLED -> LomoDataStoreKeys.LAN_SHARE_ENABLED
        SettingsKey.LAN_SHARE_E2E_ENABLED -> LomoDataStoreKeys.LAN_SHARE_E2E_ENABLED
        SettingsKey.SYNC_INBOX_ENABLED -> LomoDataStoreKeys.SYNC_INBOX_ENABLED
        SettingsKey.MEMO_SNAPSHOTS_ENABLED -> LomoDataStoreKeys.MEMO_SNAPSHOTS_ENABLED
        SettingsKey.GIT_SYNC_ENABLED -> LomoDataStoreKeys.GIT_SYNC_ENABLED
        SettingsKey.GIT_AUTO_SYNC_ENABLED -> LomoDataStoreKeys.GIT_AUTO_SYNC_ENABLED
        SettingsKey.GIT_SYNC_ON_REFRESH -> LomoDataStoreKeys.GIT_SYNC_ON_REFRESH
        SettingsKey.WEBDAV_SYNC_ENABLED -> LomoDataStoreKeys.WEBDAV_SYNC_ENABLED
        SettingsKey.WEBDAV_AUTO_SYNC_ENABLED -> LomoDataStoreKeys.WEBDAV_AUTO_SYNC_ENABLED
        SettingsKey.WEBDAV_SYNC_ON_REFRESH -> LomoDataStoreKeys.WEBDAV_SYNC_ON_REFRESH
        SettingsKey.S3_SYNC_ENABLED -> LomoDataStoreKeys.S3_SYNC_ENABLED
        SettingsKey.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION -> LomoDataStoreKeys.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION
        SettingsKey.S3_RCLONE_DATA_ENCRYPTION_ENABLED -> LomoDataStoreKeys.S3_RCLONE_DATA_ENCRYPTION_ENABLED
        SettingsKey.S3_AUTO_SYNC_ENABLED -> LomoDataStoreKeys.S3_AUTO_SYNC_ENABLED
        SettingsKey.S3_SYNC_ON_REFRESH -> LomoDataStoreKeys.S3_SYNC_ON_REFRESH
        else -> error("Migration setting $this is not a boolean preference")
    }

internal fun String.requireIntPreferenceKey(): Preferences.Key<Int> =
    when (this) {
        SettingsKey.MEMO_SNAPSHOT_MAX_COUNT -> LomoDataStoreKeys.MEMO_SNAPSHOT_MAX_COUNT
        SettingsKey.MEMO_SNAPSHOT_MAX_AGE_DAYS -> LomoDataStoreKeys.MEMO_SNAPSHOT_MAX_AGE_DAYS
        else -> error("Migration setting $this is not an integer preference")
    }
