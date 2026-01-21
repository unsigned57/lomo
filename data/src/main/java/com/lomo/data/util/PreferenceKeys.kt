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

    // Default values
    object Defaults {
        const val DATE_FORMAT = "yyyy-MM-dd"
        const val TIME_FORMAT = "HH:mm"
        const val HAPTIC_FEEDBACK_ENABLED = true
        const val CHECK_UPDATES_ON_STARTUP = true
        const val THEME_MODE = "system"

        // Storage Defaults
        const val STORAGE_FILENAME_FORMAT = "yyyy_MM_dd"
        const val STORAGE_TIMESTAMP_FORMAT = "HH:mm:ss"
    }
}
