package com.lomo.data.local.entity

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val memoStringListJson = Json

internal fun decodeStoredMemoStringList(value: String): List<String> {
    if (value.isEmpty()) {
        return emptyList()
    }
    val trimmed = value.trim()
    if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
        // behavior-contract: silent-result-ok: JSON decode fail → legacy comma-split path takes over
        runCatching {
            memoStringListJson.decodeFromString(ListSerializer(String.serializer()), trimmed)
        }.getOrNull()?.let { return it }
    }
    return trimmed.split(",")
}

internal fun encodeStoredMemoStringList(values: List<String>): String =
    values
        .takeIf(List<String>::isNotEmpty)
        ?.let { memoStringListJson.encodeToString(ListSerializer(String.serializer()), it) }
        .orEmpty()
