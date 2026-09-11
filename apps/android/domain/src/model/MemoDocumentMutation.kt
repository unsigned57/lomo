package com.lomo.domain.model

/**
 * Complete facts parsed by the Rust workspace owner after one document mutation.
 *
 * The value is deliberately richer than a body string: projection consumers must never infer
 * tags, attachments, todo state, URLs, or reminder identities from the edited text a second time.
 */
data class MemoDocumentFacts(
    val memoId: String,
    val sourcePath: String,
    val fileFingerprint: String,
    val chronologyEpochMs: Long,
    val content: String,
    val tags: List<String>,
    val attachmentPaths: List<String>,
    val reminders: List<ReminderMarker>,
    val hasTodo: Boolean,
    val hasUrl: Boolean,
)

/**
 * One already-committed workspace document mutation waiting for projection convergence.
 *
 * [expectedRevision] and [expectedFingerprint] are the edit session's CAS baseline. They are not
 * re-read at commit time; a changed projection is surfaced as a stale snapshot.
 */
data class MemoDocumentMutation(
    val operationId: String,
    val expectedRevision: Long,
    val expectedFingerprint: String,
    val facts: MemoDocumentFacts,
)
