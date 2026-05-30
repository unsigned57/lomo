package com.lomo.data.repository

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class MigrationSettingsSnapshot(
    val preferences: Map<String, String> = emptyMap(),
    val sensitive: Map<String, String> = emptyMap(),
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
