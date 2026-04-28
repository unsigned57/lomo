package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

private const val MEMO_FILE_OUTBOX_OP_CREATE = 0
private const val MEMO_FILE_OUTBOX_OP_UPDATE = 1
private const val MEMO_FILE_OUTBOX_OP_DELETE = 2
private const val MEMO_FILE_OUTBOX_OP_RESTORE = 3

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
    val operation: MemoFileOutboxOp,
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

enum class MemoFileOutboxOp(
    val persistedValue: Int,
) {
    CREATE(MEMO_FILE_OUTBOX_OP_CREATE),
    UPDATE(MEMO_FILE_OUTBOX_OP_UPDATE),
    DELETE(MEMO_FILE_OUTBOX_OP_DELETE),
    RESTORE(MEMO_FILE_OUTBOX_OP_RESTORE),
    ;

    companion object {
        fun fromPersistedValue(value: Int): MemoFileOutboxOp =
            entries.firstOrNull { operation -> operation.persistedValue == value }
                ?: throw IllegalArgumentException("Unknown memo file outbox operation value: $value")
    }
}
