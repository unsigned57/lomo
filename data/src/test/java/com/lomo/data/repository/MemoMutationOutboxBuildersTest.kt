package com.lomo.data.repository

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */



import com.lomo.data.local.entity.MemoFileOutboxEntity
import com.lomo.data.local.entity.MemoFileOutboxOp
import com.lomo.data.util.MemoLocalDateResolver
import com.lomo.domain.model.Memo
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: MemoMutationOutboxBuilders
 * - Behavior focus: memo-to-outbox mapping for update, delete, restore operations and outbox-to-source-memo reconstruction.
 * - Observable outcomes: operation type, mapped identifiers/date or timestamp/raw content fields, new-content handling, and reconstructed memo localDate/content values.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: outbox DAO persistence behavior, mutation scheduling, and file-system side effects.
 */
class MemoMutationOutboxBuildersTest : DataFunSpec() {
    init {
        test("buildUpdateOutbox maps update operation and new content fields") { `buildUpdateOutbox maps update operation and new content fields`() }

        test("buildDeleteOutbox maps delete operation with empty new content") { `buildDeleteOutbox maps delete operation with empty new content`() }

        test("buildRestoreOutbox maps restore operation with source memo content") { `buildRestoreOutbox maps restore operation with source memo content`() }

        test("outboxSourceMemo reconstructs source-facing memo fields") { `outboxSourceMemo reconstructs source-facing memo fields`() }

        test("outboxSourceMemo defaults content to empty when outbox new content is null") { `outboxSourceMemo defaults content to empty when outbox new content is null`() }
    }


    private val sourceMemo =
        Memo(
            id = "memo_1",
            timestamp = 1_711_500_100_000L,
            updatedAt = 1_711_500_200_000L,
            content = "source content",
            rawContent = "- 09:15 source content",
            dateKey = "2026_03_27",
        )

    private fun `buildUpdateOutbox maps update operation and new content fields`() {
        val result = buildUpdateOutbox(sourceMemo = sourceMemo, newContent = "updated content")

        result.operation shouldBe MemoFileOutboxOp.UPDATE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent shouldBe "updated content"
        result.createRawContent.shouldBeNull()
    }

    private fun `buildDeleteOutbox maps delete operation with empty new content`() {
        val result = buildDeleteOutbox(sourceMemo = sourceMemo)

        result.operation shouldBe MemoFileOutboxOp.DELETE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent.shouldBeNull()
        result.createRawContent.shouldBeNull()
    }

    private fun `buildRestoreOutbox maps restore operation with source memo content`() {
        val result = buildRestoreOutbox(sourceMemo = sourceMemo)

        result.operation shouldBe MemoFileOutboxOp.RESTORE
        result.memoId shouldBe sourceMemo.id
        result.memoDate shouldBe sourceMemo.dateKey
        result.memoTimestamp shouldBe sourceMemo.timestamp
        result.memoRawContent shouldBe sourceMemo.rawContent
        result.newContent shouldBe sourceMemo.content
        result.createRawContent.shouldBeNull()
    }

    private fun `outboxSourceMemo reconstructs source-facing memo fields`() {
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

        memo.id shouldBe "memo_2"
        memo.timestamp shouldBe 1_711_600_000_000L
        memo.content shouldBe "rebuilt"
        memo.rawContent shouldBe "- 10:30 rebuilt"
        memo.dateKey shouldBe "2026_03_28"
        memo.localDate shouldBe MemoLocalDateResolver.resolve("2026_03_28")
    }

    private fun `outboxSourceMemo defaults content to empty when outbox new content is null`() {
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

        memo.content shouldBe ""
        memo.rawContent shouldBe "- 11:00 deleted"
    }
}
