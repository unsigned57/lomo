package com.lomo.data.memo

import javax.inject.Inject

/**
 * Single source of truth for memo identity and timestamp offset rules.
 */
class MemoIdentityPolicy
    @Inject
    constructor() {
        fun buildBaseId(
            dateKey: String,
            timePart: String,
            content: String,
        ): String {
            val contentHash = contentHashHex(content)
            return "${dateKey}_${timePart}_$contentHash"
        }

        fun applyCollisionSuffix(
            baseId: String,
            collisionIndex: Int,
        ): String = if (collisionIndex <= 0) baseId else "${baseId}_$collisionIndex"

        fun nextCollisionIndex(
            existingIds: Set<String>,
            baseId: String,
        ): Int {
            if (baseId !in existingIds) return 0
            var index = 1
            while (applyCollisionSuffix(baseId, index) in existingIds) {
                index++
            }
            return index
        }

        fun applyTimestampOffset(
            baseTimestampMillis: Long,
            occurrenceIndex: Int,
        ): Long {
            val safeOffset = occurrenceIndex.coerceIn(0, MAX_TIMESTAMP_OFFSET_MS)
            return baseTimestampMillis + safeOffset
        }

        fun matchesBaseOrCollision(
            memoId: String,
            baseId: String,
        ): Boolean = memoId == baseId || memoId.startsWith("${baseId}_")

        companion object {
            fun contentHashHex(content: String): String = MemoContentHashPolicy.hashHex(content)

            private const val MAX_TIMESTAMP_OFFSET_MS = 999
        }
    }
