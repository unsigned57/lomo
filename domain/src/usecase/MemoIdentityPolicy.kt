package com.lomo.domain.usecase

/**
 * Single owner of memo identity.
 *
 * A memo id is content-independent and positional:
 * `"${dateKey}_${timePart}_${ordinal}"`, where `ordinal` is the 0-based position among
 * memos sharing the same `(dateKey, timePart)` in file/append order.
 *
 * Because the id never embeds the content, editing a memo's body does not change its id.
 * The DB projection and the source `.md` file therefore stay aligned: block lookups,
 * outbox file flush, and reconcile all match a memo to its file block by this stable id.
 */
class MemoIdentityPolicy {
    fun buildId(
        dateKey: String,
        timePart: String,
        ordinal: Int,
    ): String = "${dateKey}_${timePart}_${ordinal.coerceAtLeast(0)}"

    fun applyTimestampOffset(
        baseTimestampMillis: Long,
        occurrenceIndex: Int,
    ): Long {
        val safeOffset = occurrenceIndex.coerceIn(0, MAX_TIMESTAMP_OFFSET_MS)
        return baseTimestampMillis + safeOffset
    }

    companion object {
        private const val MAX_TIMESTAMP_OFFSET_MS = 999
    }
}
