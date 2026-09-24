package com.lomo.domain.model

/**
 * Recorded fact that the preferences store had to quarantine a corrupted file.
 *
 * The notice is the user-facing half of the quarantine: the original bytes were moved aside for
 * evidence, the session runs on empty preferences, and this notice is what lets the app present
 * recovery instead of silently losing settings.
 */
data class PreferencesCorruptionNotice(
    val quarantinedFileName: String,
    val diagnostic: String,
)
