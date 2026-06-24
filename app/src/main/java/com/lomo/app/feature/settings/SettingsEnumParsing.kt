package com.lomo.app.feature.settings

internal inline fun <reified T : Enum<T>> enumValueOrDefault(
    raw: String?,
    default: T,
): T = raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
