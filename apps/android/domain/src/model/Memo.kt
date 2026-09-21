package com.lomo.domain.model

import java.time.LocalDate

/**
 * Describes how much of a memo body a read projection carries.
 *
 * A list row is intentionally bounded and therefore cannot be used as an edit or detail fact.
 * Keeping this bit in the domain value prevents caches from aliasing a preview and a complete
 * snapshot that happen to share the same memo revision.
 */
enum class MemoContentKind {
    Full,
    Preview,
}

data class Memo(
    val id: String, // Unique ID (e.g. timestamp hash or UUID)
    val timestamp: Long,
    val updatedAt: Long = timestamp,
    val content: String,
    val rawContent: String, // Full line content including timestamp
    val dateKey: String, // Filename stem (e.g. "2026_02_27"), format varies
    val localDate: LocalDate? = null,
    val tags: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val isDeleted: Boolean = false,
    /** Engine accepted the create; the durable commit has not landed yet. */
    val isPending: Boolean = false,
    val geoLocation: String? = null, // "lat,lng" coordinate pair
    val reminders: List<ReminderMarker> = emptyList(),
    /** Content revision observed when this memo entered the edit session. */
    val contentRevision: Long? = null,
    /** File fingerprint observed with [contentRevision]. */
    val fileFingerprint: String? = null,
    /** Whether [content] is a bounded list projection rather than the complete body. */
    val contentKind: MemoContentKind = MemoContentKind.Full,
    /**
     * Full-document character count from the store projection.
     *
     * List preview rows still carry a bounded [content]; this count is the expand-affordance fact
     * and must not be inferred from preview length. Null means the caller did not observe a
     * projection (tests and non-store surfaces).
     */
    val projectedCharCount: Long? = null,
) {
    /**
     * Domain-facing alias for raw source text persisted for this memo.
     */
    val sourceSnapshot: String
        get() = rawContent

    /**
     * Domain-facing alias for the day partition key used to group memo entries.
     */
    val dayBucketKey: String
        get() = dateKey
}
