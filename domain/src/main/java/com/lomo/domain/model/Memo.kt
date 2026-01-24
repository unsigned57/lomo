package com.lomo.domain.model

data class Memo(
    val id: String, // Unique ID (e.g. timestamp hash or UUID)
    val timestamp: Long,
    val content: String,
    val rawContent: String, // Full line content including timestamp
    val date: String, // YYYY_MM_DD
    val tags: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val isDeleted: Boolean = false,
)
