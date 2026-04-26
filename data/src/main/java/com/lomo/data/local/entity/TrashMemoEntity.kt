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
            tags = decodeStoredMemoStringList(tags),
            imageUrls = decodeStoredMemoStringList(imageUrls),
            isPinned = isPinned,
            isDeleted = true,
        )
        }

    companion object {
        fun fromDomain(memo: Memo): TrashMemoEntity =
            TrashMemoEntity(
                id = memo.id,
                timestamp = memo.timestamp,
                updatedAt = memo.updatedAt,
                content = memo.content,
                rawContent = memo.rawContent,
                date = memo.dateKey,
                tags = encodeStoredMemoStringList(memo.tags),
                imageUrls = encodeStoredMemoStringList(memo.imageUrls),
            )
    }
}
