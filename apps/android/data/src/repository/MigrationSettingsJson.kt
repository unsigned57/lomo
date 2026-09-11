package com.lomo.data.repository

import com.lomo.domain.usecase.MigrationSettingsSummary
import kotlinx.serialization.json.Json

/** Shared JSON codec for encrypted settings envelopes (not workspace ZIP archives). */
internal val migrationJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

internal interface MigrationSettingsRestoreValidator {
    suspend fun validateRestore(snapshot: MigrationSettingsSnapshot): MigrationSettingsValidationReport
}

internal fun MigrationSettingsSnapshot.toSummary(): MigrationSettingsSummary =
    MigrationSettingsSummary(
        settingCount = preferences.size + sensitive.size,
        sensitiveSettingCount = sensitive.size,
    )
