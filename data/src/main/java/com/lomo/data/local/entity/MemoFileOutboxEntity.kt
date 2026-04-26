package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "MemoFileOutbox",
    indices =
        [
            Index(value = ["memoId"]),
            Index(value = ["createdAt"]),
            Index(value = ["claimToken"]),
            Index(value = ["claimUpdatedAt"]),
        ],
)
data class MemoFileOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val operation: String,
    val memoId: String,
    val memoDate: String,
    val memoTimestamp: Long,
    val memoRawContent: String,
    val newContent: String?,
    val createRawContent: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null,
    val claimToken: String? = null,
    val claimUpdatedAt: Long? = null,
)

object MemoFileOutboxOp {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val RESTORE = "RESTORE"
}
