package com.lomo.domain.model

import java.time.LocalDate

data class Memo(
    val id: String, // Unique ID (e.g. timestamp hash or UUID)
    val timestamp: Long,
    val content: String,
    val rawContent: String, // Full line content including timestamp
    val dateKey: String, // Filename stem (e.g. "2026_02_27"), format varies
    val localDate: LocalDate? = null,
    val tags: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val isDeleted: Boolean = false,
)
