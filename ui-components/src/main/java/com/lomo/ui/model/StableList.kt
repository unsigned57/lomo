package com.lomo.ui.model

import androidx.compose.runtime.Immutable

@Immutable data class StableList<T>(
    val items: List<T> = emptyList(),
) : List<T> by items
