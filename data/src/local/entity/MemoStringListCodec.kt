package com.lomo.data.local.entity

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val memoStringListJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

internal fun decodeStoredMemoStringList(value: String): List<String> {
    if (value.isEmpty()) {
        return emptyList()
    }
    val trimmed = value.trim()
    if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
        // behavior-contract: silent-result-ok: JSON decode fail → custom robust fallback parser takes over
        runCatching {
            memoStringListJson.decodeFromString(ListSerializer(String.serializer()), trimmed)
        }.getOrNull()?.let { return it }

        // behavior-contract: silent-result-ok: fallback parser when json fails
        runCatching {
            val result = mutableListOf<String>()
            var inQuotes = false
            var isEscaped = false
            val current = StringBuilder()
            var hasAnyItem = false
            for (i in 1 until trimmed.length - 1) {
                val c = trimmed[i]
                when {
                    isEscaped -> {
                        current.append(c)
                        isEscaped = false
                    }
                    c == '\\' -> {
                        isEscaped = true
                    }
                    c == '"' -> {
                        inQuotes = !inQuotes
                        hasAnyItem = true
                    }
                    c == ',' && !inQuotes -> {
                        result.add(current.toString().trim())
                        current.setLength(0)
                    }
                    else -> {
                        if (inQuotes || !c.isWhitespace()) {
                            current.append(c)
                        }
                    }
                }
            }
            val lastItem = current.toString().trim()
            if (lastItem.isNotEmpty() || hasAnyItem || result.isNotEmpty()) {
                result.add(lastItem)
            }
            result
        }.getOrNull()?.let { return it }
    }
    return trimmed.split(",")
}

internal fun encodeStoredMemoStringList(values: List<String>): String =
    values
        .takeIf(List<String>::isNotEmpty)
        ?.let { memoStringListJson.encodeToString(ListSerializer(String.serializer()), it) }
        .orEmpty()
