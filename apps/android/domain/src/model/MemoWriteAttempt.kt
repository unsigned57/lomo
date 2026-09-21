package com.lomo.domain.model

private const val MAX_MEMO_OPERATION_ID_LENGTH = 128

/**
 * A client retry token, frozen once for a logical command before it reaches an adapter.
 *
 * The alphabet is the Rust `OperationId` alphabet: ASCII letters, digits, `-`, `_`, `.` and `:`.
 * Kotlin must not accept a wider set, or a locally valid token would fail at the owner boundary.
 */
@JvmInline
value class MemoOperationId(val value: String) {
    init {
        require(
            value.isNotBlank() &&
                value.length <= MAX_MEMO_OPERATION_ID_LENGTH &&
                value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_.:" },
        ) {
            "Memo operation id must be a bounded nonblank ASCII protocol token"
        }
    }
}

class EditBaseline internal constructor(
    val memoId: String,
    val contentRevision: Long,
    val fileFingerprint: String,
) {
    init {
        require(memoId.isNotBlank() && contentRevision > 0 && fileFingerprint.isNotBlank()) {
            "An edit baseline requires stable identity, revision and source fingerprint"
        }
    }
}

/** A list preview cannot become an edit command without obtaining a complete source snapshot. */
class EditableMemoSnapshot private constructor(
    val memo: Memo,
    val baseline: EditBaseline,
) {
    override fun equals(other: Any?): Boolean = other is EditableMemoSnapshot && memo == other.memo
    override fun hashCode(): Int = memo.hashCode()

    companion object {
        fun fromFullSnapshot(memo: Memo): EditableMemoSnapshot {
            require(memo.contentKind == MemoContentKind.Full && !memo.isPending) {
                "Editing requires a complete, committed memo snapshot"
            }
            val revision = requireNotNull(memo.contentRevision) { "Edit snapshot lacks a content revision" }
            val fingerprint = requireNotNull(memo.fileFingerprint) { "Edit snapshot lacks a source fingerprint" }
            return EditableMemoSnapshot(memo, EditBaseline(memo.id, revision, fingerprint))
        }
    }
}

data class MemoCreateAttempt(
    val operationId: MemoOperationId,
    /** The draft that staged this memo's media; its leases transfer to the operation on submit. */
    val draftId: DraftId,
    val content: String,
    val timestampMillis: Long,
)

data class MemoUpdateAttempt(
    val operationId: MemoOperationId,
    /** The draft that staged this memo's media; its leases transfer to the operation on submit. */
    val draftId: DraftId,
    val snapshot: EditableMemoSnapshot,
    val content: String,
)
