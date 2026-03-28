package com.lomo.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo

@Entity(
    tableName = "Lomo",
    indices =
        [
            Index(value = ["timestamp"]),
            Index(value = ["updatedAt"]),
            Index(value = ["date"]),
        ],
)
data class MemoEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val updatedAt: Long = timestamp,
    val content: String,
    val rawContent: String,
    val date: String,
    val tags: String, // Comma separated for simplicity or JSON
    val imageUrls: String, // Comma separated
) {
    fun toDomain(isPinned: Boolean = false): Memo =
        StoredMemoRecovery.recoverOrNull(
            rawContent = rawContent,
            storedContent = content,
            storedTimestamp = timestamp,
            dateKey = date,
        ).let { recovered ->
            Memo(
            id = id,
            timestamp = recovered?.timestamp ?: timestamp,
            updatedAt = if (recovered != null && updatedAt == timestamp) recovered.timestamp else updatedAt,
            content = recovered?.content ?: content,
            rawContent = rawContent,
            dateKey = date,
            localDate = MemoLocalDateResolver.resolve(date),
            tags = if (tags.isEmpty()) emptyList() else tags.split(","),
            imageUrls = if (imageUrls.isEmpty()) emptyList() else imageUrls.split(","),
            isPinned = isPinned,
            isDeleted = false,
        )
        }

    companion object {
        fun fromDomain(memo: Memo): MemoEntity =
            MemoEntity(
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
