package com.lomo.ui.model

@Deprecated(
    message = "StableList is a legacy compatibility wrapper. Prefer ImmutableList<T> directly.",
    replaceWith = ReplaceWith("items"),
    level = DeprecationLevel.WARNING,
)
data class StableList<T>(
    val items: List<T> = emptyList(),
) : List<T> by items
