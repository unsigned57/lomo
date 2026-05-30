package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.ColumnInfo
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo

@Entity(
    tableName = "Lomo",
    indices =
        [
            Index(value = ["timestamp"]),
            Index(value = ["updatedAt"]),
            Index(value = ["date"]),
            Index(value = ["hasTodo"]),
            Index(value = ["hasAttachment"]),
            Index(value = ["hasUrl"]),
        ],
)
data class MemoEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val updatedAt: Long = timestamp,
    val content: String,
    val searchContent: String,
    val rawContent: String,
    val date: String,
    val tags: String, // Comma separated for simplicity or JSON
    val imageUrls: String, // Comma separated
    @ColumnInfo(defaultValue = "0") val hasTodo: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hasAttachment: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hasUrl: Boolean = false,
    @ColumnInfo(defaultValue = "0") val statisticsWordCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val statisticsCharacterCount: Int = 0,
    val geoLocation: String? = null, // "lat,lng" coordinate pair
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
            isDeleted = false,
            geoLocation = geoLocation,
        )
}
