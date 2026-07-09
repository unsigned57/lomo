package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo

@Entity(
    tableName = "LomoTrash",
    indices =
        [
            Index(value = ["timestamp"]),
            Index(value = ["updatedAt"]),
            Index(value = ["date"]),
        ],
)
data class TrashMemoEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val updatedAt: Long = timestamp,
    val content: String,
    val rawContent: String,
    val date: String,
    val tags: String,
    val imageUrls: String,
) {
    fun toDomain(isPinned: Boolean = false): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            updatedAt = updatedAt,
            content = content,
            rawContent = rawContent,
            dateKey = date,
            localDate = MemoLocalDateResolver.resolve(date),
            tags = decodeStoredMemoStringList(tags),
            imageUrls = decodeStoredMemoStringList(imageUrls),
            isPinned = isPinned,
            isDeleted = true,
        )
}
