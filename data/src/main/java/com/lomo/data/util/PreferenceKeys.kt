package com.lomo.data.util

import com.lomo.domain.model.PreferenceDefaults

/**
 * Centralized preference key constants to avoid magic strings and ensure consistency across the
 * app.
 */
object PreferenceKeys {
    const val PREFS_NAME = "lomo_prefs"

    // Storage
    const val ROOT_URI = "root_uri"
    const val ROOT_DIRECTORY = "root_directory"
    const val IMAGE_URI = "image_uri"
    const val IMAGE_DIRECTORY = "image_directory"
    const val VOICE_URI = "voice_uri"
    const val VOICE_DIRECTORY = "voice_directory"
    const val SYNC_INBOX_URI = "sync_inbox_uri"
    const val SYNC_INBOX_DIRECTORY = "sync_inbox_directory"

    // Storage Formats (New)
    const val STORAGE_FILENAME_FORMAT = "storage_filename_format"
    const val STORAGE_TIMESTAMP_FORMAT = "storage_timestamp_format"

    // Display
    const val DATE_FORMAT = "date_format_only"
    const val TIME_FORMAT = "time_format_only"
    const val THEME_MODE = "theme_mode"

    // Interaction
    const val HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
    const val CHECK_UPDATES_ON_STARTUP = "check_updates_on_startup"
    const val SHOW_INPUT_HINTS = "show_input_hints"
    const val DOUBLE_TAP_EDIT_ENABLED = "double_tap_edit_enabled"
    const val FREE_TEXT_COPY_ENABLED = "free_text_copy_enabled"
    const val MEMO_ACTION_AUTO_REORDER_ENABLED = "memo_action_auto_reorder_enabled"
    const val MEMO_ACTION_ORDER = "memo_action_order"
    const val QUICK_SAVE_ON_BACK_ENABLED = "quick_save_on_back_enabled"
    const val SCROLLBAR_ENABLED = "scrollbar_enabled"
    const val APP_LOCK_ENABLED = "app_lock_enabled"
    const val LAN_SHARE_PAIRING_KEY_HEX = "lan_share_pairing_key_hex"
    const val LAN_SHARE_E2E_ENABLED = "lan_share_e2e_enabled"
    const val LAN_SHARE_DEVICE_NAME = "lan_share_device_name"
    const val SHARE_CARD_SHOW_TIME = "share_card_show_time"
    const val SHARE_CARD_SHOW_BRAND = "share_card_show_brand"
    const val SHARE_CARD_SIGNATURE_TEXT = "share_card_signature_text"
    const val SYNC_INBOX_ENABLED = "sync_inbox_enabled"
    const val DAILY_REVIEW_SESSION_DATE = "daily_review_session_date"
    const val DAILY_REVIEW_SESSION_SEED = "daily_review_session_seed"
    const val DAILY_REVIEW_SESSION_PAGE_INDEX = "daily_review_session_page_index"
    const val MEMO_SNAPSHOTS_ENABLED = "memo_snapshots_enabled"
    const val MEMO_SNAPSHOT_MAX_COUNT = "memo_snapshot_max_count"
    const val MEMO_SNAPSHOT_MAX_AGE_DAYS = "memo_snapshot_max_age_days"
    const val LAST_APP_VERSION = "last_app_version"

    // Git Sync
    const val GIT_SYNC_ENABLED = "git_sync_enabled"
    const val GIT_REMOTE_URL = "git_remote_url"
    const val GIT_AUTHOR_NAME = "git_author_name"
    const val GIT_AUTHOR_EMAIL = "git_author_email"
    const val GIT_AUTO_SYNC_ENABLED = "git_auto_sync_enabled"
    const val GIT_AUTO_SYNC_INTERVAL = "git_auto_sync_interval"
    const val GIT_LAST_SYNC_TIME = "git_last_sync_time"
    const val GIT_SYNC_ON_REFRESH = "git_sync_on_refresh"
    const val SYNC_BACKEND_TYPE = "sync_backend_type"
    const val WEBDAV_SYNC_ENABLED = "webdav_sync_enabled"
    const val WEBDAV_PROVIDER = "webdav_provider"
    const val WEBDAV_BASE_URL = "webdav_base_url"
    const val WEBDAV_ENDPOINT_URL = "webdav_endpoint_url"
    const val WEBDAV_USERNAME = "webdav_username"
    const val WEBDAV_AUTO_SYNC_ENABLED = "webdav_auto_sync_enabled"
    const val WEBDAV_AUTO_SYNC_INTERVAL = "webdav_auto_sync_interval"
    const val WEBDAV_LAST_SYNC_TIME = "webdav_last_sync_time"
    const val WEBDAV_SYNC_ON_REFRESH = "webdav_sync_on_refresh"
    const val S3_SYNC_ENABLED = "s3_sync_enabled"
    const val S3_ENDPOINT_URL = "s3_endpoint_url"
    const val S3_REGION = "s3_region"
    const val S3_BUCKET = "s3_bucket"
    const val S3_PREFIX = "s3_prefix"
    const val S3_LOCAL_SYNC_DIRECTORY = "s3_local_sync_directory"
    const val S3_PATH_STYLE = "s3_path_style"
    const val S3_ENCRYPTION_MODE = "s3_encryption_mode"
    const val S3_RCLONE_FILENAME_ENCRYPTION = "s3_rclone_filename_encryption"
    const val S3_RCLONE_FILENAME_ENCODING = "s3_rclone_filename_encoding"
    const val S3_RCLONE_DIRECTORY_NAME_ENCRYPTION = "s3_rclone_directory_name_encryption"
    const val S3_RCLONE_DATA_ENCRYPTION_ENABLED = "s3_rclone_data_encryption_enabled"
    const val S3_RCLONE_ENCRYPTED_SUFFIX = "s3_rclone_encrypted_suffix"
    const val S3_AUTO_SYNC_ENABLED = "s3_auto_sync_enabled"
    const val S3_AUTO_SYNC_INTERVAL = "s3_auto_sync_interval"
    const val S3_LAST_SYNC_TIME = "s3_last_sync_time"
    const val S3_SYNC_ON_REFRESH = "s3_sync_on_refresh"

    // Draft
    const val DRAFT_TEXT = "draft_text"

    // Default values
    object Defaults {
        const val DATE_FORMAT = PreferenceDefaults.DATE_FORMAT
        const val TIME_FORMAT = PreferenceDefaults.TIME_FORMAT
        const val HAPTIC_FEEDBACK_ENABLED = PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED
        const val CHECK_UPDATES_ON_STARTUP = PreferenceDefaults.CHECK_UPDATES_ON_STARTUP
        const val SHOW_INPUT_HINTS = PreferenceDefaults.SHOW_INPUT_HINTS
        const val DOUBLE_TAP_EDIT_ENABLED = PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED
        const val FREE_TEXT_COPY_ENABLED = PreferenceDefaults.FREE_TEXT_COPY_ENABLED
        const val MEMO_ACTION_AUTO_REORDER_ENABLED = PreferenceDefaults.MEMO_ACTION_AUTO_REORDER_ENABLED
        const val MEMO_ACTION_ORDER = PreferenceDefaults.MEMO_ACTION_ORDER
        const val QUICK_SAVE_ON_BACK_ENABLED = PreferenceDefaults.QUICK_SAVE_ON_BACK_ENABLED
        const val SCROLLBAR_ENABLED = PreferenceDefaults.SCROLLBAR_ENABLED
        const val APP_LOCK_ENABLED = PreferenceDefaults.APP_LOCK_ENABLED
        const val LAN_SHARE_E2E_ENABLED = PreferenceDefaults.LAN_SHARE_E2E_ENABLED
        const val THEME_MODE = PreferenceDefaults.THEME_MODE
        const val SHARE_CARD_SHOW_TIME = PreferenceDefaults.SHARE_CARD_SHOW_TIME
        const val SHARE_CARD_SHOW_BRAND = PreferenceDefaults.SHARE_CARD_SHOW_BRAND
        const val SHARE_CARD_SIGNATURE_TEXT = PreferenceDefaults.SHARE_CARD_SIGNATURE_TEXT
        const val SYNC_INBOX_ENABLED = PreferenceDefaults.SYNC_INBOX_ENABLED
        const val MEMO_SNAPSHOTS_ENABLED = PreferenceDefaults.MEMO_SNAPSHOTS_ENABLED
        const val MEMO_SNAPSHOT_MAX_COUNT = PreferenceDefaults.MEMO_SNAPSHOT_MAX_COUNT
        const val MEMO_SNAPSHOT_MAX_AGE_DAYS = PreferenceDefaults.MEMO_SNAPSHOT_MAX_AGE_DAYS

        // Storage Defaults
        const val STORAGE_FILENAME_FORMAT = PreferenceDefaults.STORAGE_FILENAME_FORMAT
        const val STORAGE_TIMESTAMP_FORMAT = PreferenceDefaults.STORAGE_TIMESTAMP_FORMAT

        // Git Sync Defaults
        const val GIT_SYNC_ENABLED = PreferenceDefaults.GIT_SYNC_ENABLED
        const val GIT_AUTO_SYNC_ENABLED = PreferenceDefaults.GIT_AUTO_SYNC_ENABLED
        const val GIT_AUTO_SYNC_INTERVAL = PreferenceDefaults.GIT_AUTO_SYNC_INTERVAL
        const val GIT_AUTHOR_NAME = PreferenceDefaults.GIT_AUTHOR_NAME
        const val GIT_AUTHOR_EMAIL = PreferenceDefaults.GIT_AUTHOR_EMAIL
        const val GIT_SYNC_ON_REFRESH = PreferenceDefaults.GIT_SYNC_ON_REFRESH
        const val SYNC_BACKEND_TYPE = PreferenceDefaults.SYNC_BACKEND_TYPE
        const val WEBDAV_SYNC_ENABLED = PreferenceDefaults.WEBDAV_SYNC_ENABLED
        const val WEBDAV_PROVIDER = PreferenceDefaults.WEBDAV_PROVIDER
        const val WEBDAV_AUTO_SYNC_ENABLED = PreferenceDefaults.WEBDAV_AUTO_SYNC_ENABLED
        const val WEBDAV_AUTO_SYNC_INTERVAL = PreferenceDefaults.WEBDAV_AUTO_SYNC_INTERVAL
        const val WEBDAV_SYNC_ON_REFRESH = PreferenceDefaults.WEBDAV_SYNC_ON_REFRESH
        const val S3_SYNC_ENABLED = PreferenceDefaults.S3_SYNC_ENABLED
        const val S3_PATH_STYLE = PreferenceDefaults.S3_PATH_STYLE
        const val S3_ENCRYPTION_MODE = PreferenceDefaults.S3_ENCRYPTION_MODE
        const val S3_RCLONE_FILENAME_ENCRYPTION = PreferenceDefaults.S3_RCLONE_FILENAME_ENCRYPTION
        const val S3_RCLONE_FILENAME_ENCODING = PreferenceDefaults.S3_RCLONE_FILENAME_ENCODING
        const val S3_RCLONE_DIRECTORY_NAME_ENCRYPTION =
            PreferenceDefaults.S3_RCLONE_DIRECTORY_NAME_ENCRYPTION
        const val S3_RCLONE_DATA_ENCRYPTION_ENABLED =
            PreferenceDefaults.S3_RCLONE_DATA_ENCRYPTION_ENABLED
        const val S3_RCLONE_ENCRYPTED_SUFFIX = PreferenceDefaults.S3_RCLONE_ENCRYPTED_SUFFIX
        const val S3_AUTO_SYNC_ENABLED = PreferenceDefaults.S3_AUTO_SYNC_ENABLED
        const val S3_AUTO_SYNC_INTERVAL = PreferenceDefaults.S3_AUTO_SYNC_INTERVAL
        const val S3_SYNC_ON_REFRESH = PreferenceDefaults.S3_SYNC_ON_REFRESH
    }
}
