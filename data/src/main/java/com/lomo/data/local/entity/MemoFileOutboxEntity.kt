package com.lomo.data.local.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val MEMO_FILE_OUTBOX_OP_CREATE = 0
private const val MEMO_FILE_OUTBOX_OP_UPDATE = 1
private const val MEMO_FILE_OUTBOX_OP_DELETE = 2
private const val MEMO_FILE_OUTBOX_OP_RESTORE = 3
private const val MEMO_FILE_OUTBOX_OP_PERMANENT_DELETE = 4
private const val MEMO_FILE_OUTBOX_OP_VERSION_RESTORE = 5

@Entity(
    tableName = "MemoFileOutbox",
    indices =
        [
            Index(value = ["memoId"]),
            Index(value = ["createdAt"]),
            Index(value = ["claimToken"]),
            Index(value = ["claimUpdatedAt"]),
            Index(value = ["operationId"], unique = true),
            Index(value = ["idempotencyKey"], unique = true),
        ],
)
data class MemoFileOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val operation: MemoFileOutboxOp,
    val operationId: String,
    val idempotencyKey: String,
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
    PERMANENT_DELETE(MEMO_FILE_OUTBOX_OP_PERMANENT_DELETE),
    VERSION_RESTORE(MEMO_FILE_OUTBOX_OP_VERSION_RESTORE),
    ;

    companion object {
        fun fromPersistedValue(value: Int): MemoFileOutboxOp =
            entries.firstOrNull { operation -> operation.persistedValue == value }
                ?: throw IllegalArgumentException("Unknown memo file outbox operation value: $value")
    }
}

internal data class MemoFileOutboxIdentity(
    val operationId: String,
    val idempotencyKey: String,
)

internal enum class MemoFileOutboxIdentityKind(
    val slug: String,
) {
    CREATE("create"),
    UPDATE("update"),
    DELETE_TO_TRASH("delete-to-trash"),
    RESTORE_FROM_TRASH("restore-from-trash"),
    PERMANENT_DELETE("permanent-delete"),
    VERSION_RESTORE("version-restore"),
}

internal object MemoFileOutboxIdentityPolicy {
    fun forCreate(
        memoId: String,
        memoDate: String,
        createRawContent: String,
    ): MemoFileOutboxIdentity =
        identity(
            kind = MemoFileOutboxIdentityKind.CREATE,
            body =
                memoIdentityBody(
                    memoId = memoId,
                    memoDate = memoDate,
                    contentHash = createRawContent.toOutboxIdentityHash(),
                ),
        )

    fun forUpdate(
        memoId: String,
        memoDate: String,
        memoRawContent: String,
        newContent: String,
    ): MemoFileOutboxIdentity =
        identity(
            kind = MemoFileOutboxIdentityKind.UPDATE,
            body =
                "${identityPart("memoId", memoId)}:${identityPart("memoDate", memoDate)}:" +
                    "${memoRawContent.toOutboxIdentityHash()}:${newContent.toOutboxIdentityHash()}",
        )

    fun forDeleteToTrash(
        memoId: String,
        memoDate: String,
        memoRawContent: String,
    ): MemoFileOutboxIdentity =
        identity(
            kind = MemoFileOutboxIdentityKind.DELETE_TO_TRASH,
            body =
                memoIdentityBody(
                    memoId = memoId,
                    memoDate = memoDate,
                    contentHash = memoRawContent.toOutboxIdentityHash(),
                ),
        )

    fun forRestoreFromTrash(
        memoId: String,
        memoDate: String,
        memoRawContent: String,
    ): MemoFileOutboxIdentity =
        identity(
            kind = MemoFileOutboxIdentityKind.RESTORE_FROM_TRASH,
            body =
                memoIdentityBody(
                    memoId = memoId,
                    memoDate = memoDate,
                    contentHash = memoRawContent.toOutboxIdentityHash(),
                ),
        )

    fun forPermanentDelete(
        memoId: String,
        memoDate: String,
        trashedRawContent: String,
    ): MemoFileOutboxIdentity =
        identity(
            kind = MemoFileOutboxIdentityKind.PERMANENT_DELETE,
            body =
                memoIdentityBody(
                    memoId = memoId,
                    memoDate = memoDate,
                    contentHash = trashedRawContent.toOutboxIdentityHash(),
                ),
        )

    fun forVersionRestoreHandoff(
        memoId: String,
        currentRevisionId: String,
        currentRevisionHash: String,
        targetRevisionId: String,
        targetRevisionHash: String,
    ): MemoFileOutboxIdentity =
        identity(
            kind = MemoFileOutboxIdentityKind.VERSION_RESTORE,
            body =
                "${identityPart("memoId", memoId)}:${identityPart("currentRevisionId", currentRevisionId)}:" +
                    "${identityPart("currentRevisionHash", currentRevisionHash)}:" +
                    "${identityPart("targetRevisionId", targetRevisionId)}:" +
                    identityPart("targetRevisionHash", targetRevisionHash),
        )

    fun forOutboxOperation(
        operation: MemoFileOutboxOp,
        memoId: String,
        memoDate: String,
        memoRawContent: String,
        newContent: String?,
        createRawContent: String?,
    ): MemoFileOutboxIdentity =
        when (operation) {
            MemoFileOutboxOp.CREATE ->
                forCreate(
                    memoId = memoId,
                    memoDate = memoDate,
                    createRawContent =
                        requireNotNull(createRawContent) {
                            "CREATE outbox identity requires createRawContent for memo $memoId"
                        },
                )
            MemoFileOutboxOp.UPDATE ->
                forUpdate(
                    memoId = memoId,
                    memoDate = memoDate,
                    memoRawContent = memoRawContent,
                    newContent =
                        requireNotNull(newContent) {
                            "UPDATE outbox identity requires newContent for memo $memoId"
                        },
                )
            MemoFileOutboxOp.DELETE ->
                forDeleteToTrash(
                    memoId = memoId,
                    memoDate = memoDate,
                    memoRawContent = memoRawContent,
                )
            MemoFileOutboxOp.RESTORE ->
                forRestoreFromTrash(
                    memoId = memoId,
                    memoDate = memoDate,
                    memoRawContent = memoRawContent,
                )
            MemoFileOutboxOp.PERMANENT_DELETE ->
                forPermanentDelete(
                    memoId = memoId,
                    memoDate = memoDate,
                    trashedRawContent = memoRawContent,
                )
            MemoFileOutboxOp.VERSION_RESTORE ->
                error("VERSION_RESTORE outbox identity requires revision restore command metadata for memo $memoId")
        }

    private fun identity(
        kind: MemoFileOutboxIdentityKind,
        body: String,
    ): MemoFileOutboxIdentity {
        val idempotencyKey = "${kind.slug}:$body"
        return MemoFileOutboxIdentity(
            operationId = "memo-lifecycle:${kind.slug}:$idempotencyKey",
            idempotencyKey = idempotencyKey,
        )
    }

    private fun identityPart(
        name: String,
        value: String,
    ): String {
        require(value.isNotBlank()) { "Memo file outbox identity requires non-blank $name" }
        return value
    }

    private fun memoIdentityBody(
        memoId: String,
        memoDate: String,
        contentHash: String,
    ): String =
        "${identityPart("memoId", memoId)}:" +
            "${identityPart("memoDate", memoDate)}:" +
            identityPart("contentHash", contentHash)
}

private fun String.toOutboxIdentityHash(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
