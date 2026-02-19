package com.lomo.data.util

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
    const val LAN_SHARE_PAIRING_KEY_HEX = "lan_share_pairing_key_hex"
    const val LAN_SHARE_PAIRING_CODE_PLAIN = "lan_share_pairing_code_plain"
    const val LAN_SHARE_E2E_ENABLED = "lan_share_e2e_enabled"
    const val LAN_SHARE_DEVICE_NAME = "lan_share_device_name"
    const val LAST_APP_VERSION = "last_app_version"

    // Default values
    object Defaults {
        const val DATE_FORMAT = "yyyy-MM-dd"
        const val TIME_FORMAT = "HH:mm"
        const val HAPTIC_FEEDBACK_ENABLED = true
        const val CHECK_UPDATES_ON_STARTUP = true
        const val SHOW_INPUT_HINTS = true
        const val LAN_SHARE_E2E_ENABLED = true
        const val THEME_MODE = "system"

        // Storage Defaults
        const val STORAGE_FILENAME_FORMAT = "yyyy_MM_dd"
        const val STORAGE_TIMESTAMP_FORMAT = "HH:mm:ss"
    }
}
