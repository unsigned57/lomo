package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
    fun toDomain(): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            updatedAt = updatedAt,
            content = content,
            rawContent = rawContent,
            dateKey = date,
            localDate = MemoLocalDateResolver.resolve(date),
            tags = if (tags.isEmpty()) emptyList() else tags.split(","),
            imageUrls = if (imageUrls.isEmpty()) emptyList() else imageUrls.split(","),
            isDeleted = true,
        )

    companion object {
        fun fromDomain(memo: Memo): TrashMemoEntity =
            TrashMemoEntity(
                id = memo.id,
                timestamp = memo.timestamp,
                updatedAt = memo.updatedAt,
                content = memo.content,
                rawContent = memo.rawContent,
                date = memo.dateKey,
                tags = memo.tags.joinToString(","),
                imageUrls = memo.imageUrls.joinToString(","),
            )
    }
}
