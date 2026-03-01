package com.lomo.domain.model

object PreferenceDefaults {
    const val DATE_FORMAT = "yyyy-MM-dd"
    const val TIME_FORMAT = "HH:mm:ss"
    const val HAPTIC_FEEDBACK_ENABLED = true
    const val CHECK_UPDATES_ON_STARTUP = true
    const val SHOW_INPUT_HINTS = true
    const val DOUBLE_TAP_EDIT_ENABLED = true
    const val APP_LOCK_ENABLED = false
    const val LAN_SHARE_E2E_ENABLED = true
    const val THEME_MODE = "system"
    const val SHARE_CARD_STYLE = "clean"
    const val SHARE_CARD_SHOW_TIME = true
    const val SHARE_CARD_SHOW_BRAND = true
    const val STORAGE_FILENAME_FORMAT = StorageFilenameFormats.DEFAULT_PATTERN
    const val STORAGE_TIMESTAMP_FORMAT = StorageTimestampFormats.DEFAULT_PATTERN
    const val GIT_SYNC_ENABLED = false
    const val GIT_AUTO_SYNC_ENABLED = false
    const val GIT_AUTO_SYNC_INTERVAL = "1h"
    const val GIT_AUTHOR_NAME = "Lomo"
    const val GIT_AUTHOR_EMAIL = ""
    const val GIT_SYNC_ON_REFRESH = false
}
