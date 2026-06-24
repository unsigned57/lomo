package com.lomo.data.repository

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class MigrationSettingsSnapshot(
    val preferences: Map<String, String> = emptyMap(),
    val sensitive: Map<String, String> = emptyMap(),
)

data class MigrationSettingsManifest(
    val schemaVersion: Int,
    val destructiveActions: Set<MigrationSettingsRestoreAction>,
    val restartRequiredActions: Set<MigrationSettingsRestoreAction>,
    val reAuthRequiredActions: Set<MigrationSettingsRestoreAction>,
    val unsupportedPreferenceKeys: Set<String>,
    val unsupportedSensitiveKeys: Set<String>,
)

enum class MigrationSettingsRestoreAction {
    ORDINARY_SETTINGS_TRANSACTION,
    CREDENTIAL_IMPORT,
    CREDENTIAL_CLEAR,
    LEGACY_WEBDAV_USERNAME_DRAIN,
}

data class MigrationSettingsValidationReport(
    val manifest: MigrationSettingsManifest,
    val ordinary: MigrationOrdinarySettingsCoverage,
    val sensitive: MigrationSensitiveSettingsCoverage,
)

data class MigrationOrdinarySettingsCoverage(
    val expectedCatalogKeys: Set<String>,
    val providedCatalogKeys: Set<String>,
    val missingCatalogKeys: Set<String>,
    val expectedOrdinaryKeys: Set<String>,
    val providedOrdinaryKeys: Set<String>,
    val missingOrdinaryKeys: Set<String>,
    val missingRequiredKeys: Set<String>,
)

data class MigrationSensitiveSettingsCoverage(
    val expectedSensitiveKeys: Set<String>,
    val providedSensitiveKeys: Set<String>,
    val missingSensitiveKeys: Set<String>,
    val requiredSensitiveKeys: Set<String>,
    val providedRequiredKeys: Set<String>,
    val missingRequiredKeys: Set<String>,
    val invalidRequiredKeys: Set<String>,
)

class MigrationSettingsCoverageException(
    val report: MigrationSettingsValidationReport,
) : IllegalArgumentException(
        buildString {
            val missingOrdinary = report.ordinary.missingRequiredKeys.sorted()
            val missingSensitive = report.sensitive.missingRequiredKeys.sorted()
            val invalidSensitive = report.sensitive.invalidRequiredKeys.sorted()
            val unsupportedPreferences = report.manifest.unsupportedPreferenceKeys.sorted()
            val unsupportedSensitive = report.manifest.unsupportedSensitiveKeys.sorted()
            if (missingOrdinary.isNotEmpty()) {
                append("Migration settings payload is missing required ordinary settings: ")
                append(missingOrdinary.joinToString())
            }
            if (missingSensitive.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("; ")
                }
                append("Migration settings payload is missing required sensitive settings: ")
                append(missingSensitive.joinToString())
            }
            if (invalidSensitive.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("; ")
                }
                append("Migration settings payload has blank required sensitive settings: ")
                append(invalidSensitive.joinToString())
            }
            if (unsupportedPreferences.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("; ")
                }
                append("Unsupported migration preferences: ")
                append(unsupportedPreferences.joinToString())
            }
            if (unsupportedSensitive.isNotEmpty()) {
                if (isNotEmpty()) {
                    append("; ")
                }
                append("Unsupported migration sensitive settings: ")
                append(unsupportedSensitive.joinToString())
            }
        },
    )

interface MigrationSettingsStore {
    suspend fun snapshot(): MigrationSettingsSnapshot

    suspend fun restore(snapshot: MigrationSettingsSnapshot)
}

internal fun MutableMap<String, String>.putString(
    key: String,
    value: String,
) {
    this[key] = value
}

internal fun MutableMap<String, String>.putStringIfPresent(
    key: String,
    value: String?,
) {
    if (!value.isNullOrBlank()) {
        this[key] = value
    }
}

internal fun MutableMap<String, String>.putBoolean(
    key: String,
    value: Boolean,
) {
    this[key] = value.toString()
}

internal fun MutableMap<String, String>.putInt(
    key: String,
    value: Int,
) {
    this[key] = value.toString()
}

internal fun MutableMap<String, String>.putFloat(
    key: String,
    value: Float,
) {
    this[key] = value.toString()
}

internal fun Map<String, String>.getBoolean(key: String): Boolean? =
    get(key)?.lowercase(Locale.ROOT)?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

internal fun Map<String, String>.getInt(key: String): Int? = get(key)?.toIntOrNull()

internal fun Map<String, String>.getFloat(key: String): Float? = get(key)?.toFloatOrNull()
