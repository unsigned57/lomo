package com.lomo.data.repository

import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoMutationOutboxBuilders
 * - Behavior focus: memo-to-outbox mapping for update, delete, restore operations and outbox-to-source-memo reconstruction.
 * - Observable outcomes: operation type, mapped identifiers/date or timestamp/raw content fields, new-content handling, and reconstructed memo localDate/content values.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: outbox DAO persistence behavior, mutation scheduling, and file-system side effects.
 */
class MemoMutationOutboxBuildersTest {
    private val sourceMemo =
        Memo(
            id = "memo_1",
            timestamp = 1_711_500_100_000L,
            updatedAt = 1_711_500_200_000L,
            content = "source content",
            rawContent = "- 09:15 source content",
            dateKey = "2026_03_27",
        )

    @Test
    fun `buildUpdateOutbox maps update operation and new content fields`() {
        val result = buildUpdateOutbox(sourceMemo = sourceMemo, newContent = "updated content")

        assertEquals(MemoFileOutboxOp.UPDATE, result.operation)
        assertEquals(sourceMemo.id, result.memoId)
        assertEquals(sourceMemo.dateKey, result.memoDate)
        assertEquals(sourceMemo.timestamp, result.memoTimestamp)
        assertEquals(sourceMemo.rawContent, result.memoRawContent)
        assertEquals("updated content", result.newContent)
        assertNull(result.createRawContent)
    }

    @Test
    fun `buildDeleteOutbox maps delete operation with empty new content`() {
        val result = buildDeleteOutbox(sourceMemo = sourceMemo)

        assertEquals(MemoFileOutboxOp.DELETE, result.operation)
        assertEquals(sourceMemo.id, result.memoId)
        assertEquals(sourceMemo.dateKey, result.memoDate)
        assertEquals(sourceMemo.timestamp, result.memoTimestamp)
        assertEquals(sourceMemo.rawContent, result.memoRawContent)
        assertNull(result.newContent)
        assertNull(result.createRawContent)
    }

    @Test
    fun `buildRestoreOutbox maps restore operation with source memo content`() {
        val result = buildRestoreOutbox(sourceMemo = sourceMemo)

        assertEquals(MemoFileOutboxOp.RESTORE, result.operation)
        assertEquals(sourceMemo.id, result.memoId)
        assertEquals(sourceMemo.dateKey, result.memoDate)
        assertEquals(sourceMemo.timestamp, result.memoTimestamp)
        assertEquals(sourceMemo.rawContent, result.memoRawContent)
        assertEquals(sourceMemo.content, result.newContent)
        assertNull(result.createRawContent)
    }

    @Test
    fun `outboxSourceMemo reconstructs source-facing memo fields`() {
        val outbox =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.UPDATE,
                memoId = "memo_2",
                memoDate = "2026_03_28",
                memoTimestamp = 1_711_600_000_000L,
                memoRawContent = "- 10:30 rebuilt",
                newContent = "rebuilt",
                createRawContent = null,
            )

        val memo = outboxSourceMemo(outbox)

        assertEquals("memo_2", memo.id)
        assertEquals(1_711_600_000_000L, memo.timestamp)
        assertEquals("rebuilt", memo.content)
        assertEquals("- 10:30 rebuilt", memo.rawContent)
        assertEquals("2026_03_28", memo.dateKey)
        assertEquals(MemoLocalDateResolver.resolve("2026_03_28"), memo.localDate)
    }

    @Test
    fun `outboxSourceMemo defaults content to empty when outbox new content is null`() {
        val outbox =
            MemoFileOutboxEntity(
                operation = MemoFileOutboxOp.DELETE,
                memoId = "memo_3",
                memoDate = "2026_03_29",
                memoTimestamp = 1_711_700_000_000L,
                memoRawContent = "- 11:00 deleted",
                newContent = null,
                createRawContent = null,
            )

        val memo = outboxSourceMemo(outbox)

        assertEquals("", memo.content)
        assertEquals("- 11:00 deleted", memo.rawContent)
    }
}
