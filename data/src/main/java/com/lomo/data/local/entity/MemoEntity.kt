package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lomo.domain.model.Memo

@Entity(
    tableName = "Lomo",
    indices =
        [Index(value = ["timestamp"]), Index(value = ["date"]), Index(value = ["content"])],
)
data class MemoEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val content: String,
    val rawContent: String,
    val date: String,
    val tags: String, // Comma separated for simplicity or JSON
    val imageUrls: String, // Comma separated
    val isDeleted: Boolean = false,
) {
    fun toDomain(): Memo =
        Memo(
            id = id,
            timestamp = timestamp,
            content = content,
            rawContent = rawContent,
            date = date,
            tags = if (tags.isEmpty()) emptyList() else tags.split(","),
            imageUrls = if (imageUrls.isEmpty()) emptyList() else imageUrls.split(","),
            isDeleted = isDeleted,
        )

    companion object {
        fun fromDomain(memo: Memo): MemoEntity =
            MemoEntity(
                id = memo.id,
                timestamp = memo.timestamp,
                content = memo.content,
                rawContent = memo.rawContent,
                date = memo.date,
                tags = memo.tags.joinToString(","),
                imageUrls = memo.imageUrls.joinToString(","),
                isDeleted = memo.isDeleted,
            )
    }
}
