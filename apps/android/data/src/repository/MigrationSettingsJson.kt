package com.lomo.data.repository

import com.lomo.domain.usecase.MigrationSettingsSummary
import kotlinx.serialization.json.Json

/**
 * Shared JSON codec for encrypted settings envelopes (not workspace ZIP archives).
 * Strict about unknown keys: a payload carrying fields this build cannot interpret must be
 * refused as incompatible rather than silently decoded into a partial restore.
 */
internal val migrationJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

internal interface MigrationSettingsRestoreValidator {
    suspend fun validateRestore(snapshot: MigrationSettingsSnapshot): MigrationSettingsValidationReport
}

internal fun MigrationSettingsSnapshot.toSummary(): MigrationSettingsSummary =
    MigrationSettingsSummary(
        settingCount = preferences.size + sensitive.size,
        sensitiveSettingCount = sensitive.size,
    )
