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
    const val LAN_SHARE_PAIRING_KEY_HEX = "lan_share_pairing_key_hex"
    const val LAN_SHARE_E2E_ENABLED = "lan_share_e2e_enabled"
    const val LAN_SHARE_DEVICE_NAME = "lan_share_device_name"
    const val SHARE_CARD_STYLE = "share_card_style"
    const val SHARE_CARD_SHOW_TIME = "share_card_show_time"
    const val SHARE_CARD_SHOW_BRAND = "share_card_show_brand"
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

    // Default values
    object Defaults {
        const val DATE_FORMAT = PreferenceDefaults.DATE_FORMAT
        const val TIME_FORMAT = PreferenceDefaults.TIME_FORMAT
        const val HAPTIC_FEEDBACK_ENABLED = PreferenceDefaults.HAPTIC_FEEDBACK_ENABLED
        const val CHECK_UPDATES_ON_STARTUP = PreferenceDefaults.CHECK_UPDATES_ON_STARTUP
        const val SHOW_INPUT_HINTS = PreferenceDefaults.SHOW_INPUT_HINTS
        const val DOUBLE_TAP_EDIT_ENABLED = PreferenceDefaults.DOUBLE_TAP_EDIT_ENABLED
        const val LAN_SHARE_E2E_ENABLED = PreferenceDefaults.LAN_SHARE_E2E_ENABLED
        const val THEME_MODE = PreferenceDefaults.THEME_MODE
        const val SHARE_CARD_STYLE = PreferenceDefaults.SHARE_CARD_STYLE
        const val SHARE_CARD_SHOW_TIME = PreferenceDefaults.SHARE_CARD_SHOW_TIME
        const val SHARE_CARD_SHOW_BRAND = PreferenceDefaults.SHARE_CARD_SHOW_BRAND

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
    }
}
