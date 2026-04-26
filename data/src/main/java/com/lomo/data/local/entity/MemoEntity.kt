package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.data.util.SearchTokenizer
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
    val searchContent: String = SearchTokenizer.tokenize(content),
    val rawContent: String,
    val date: String,
    val tags: String, // Comma separated for simplicity or JSON
    val imageUrls: String, // Comma separated
    val geoLocation: String? = null, // "lat,lng" coordinate pair
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
            isDeleted = false,
            geoLocation = geoLocation,
        )
        }

    companion object {
        fun fromDomain(memo: Memo): MemoEntity =
            MemoEntity(
                id = memo.id,
                timestamp = memo.timestamp,
                updatedAt = memo.updatedAt,
                content = memo.content,
                searchContent = SearchTokenizer.tokenize(memo.content),
                rawContent = memo.rawContent,
                date = memo.dateKey,
                tags = encodeStoredMemoStringList(memo.tags),
                imageUrls = encodeStoredMemoStringList(memo.imageUrls),
                geoLocation = memo.geoLocation,
            )
    }
}
