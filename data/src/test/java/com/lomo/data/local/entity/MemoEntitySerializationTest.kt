package com.lomo.data.local.entity


import com.lomo.domain.model.Memo
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: MemoEntity.fromDomain / MemoEntity.toDomain
 * - Behavior focus: main-table memo serialization keeps user-visible plaintext while list fields stay JSON encoded,
 *   and deserialization continues to read both JSON and legacy CSV list formats.
 * - Observable outcomes: persisted content text, encoded tag/image fields, and decoded domain tag/image lists.
 * - Red phase: Fails before the fix because MemoEntity.fromDomain tokenizes memo.content and stores search text in
 *   the main memo table instead of preserving the original plaintext body.
 * - Excludes: FTS table writes, Room query execution, and stale-content recovery from older persisted rows.
 */
class MemoEntitySerializationTest : DataFunSpec() {
    init {
        test("fromDomain stores plaintext content plus tag and attachment lists as json text") { `fromDomain stores plaintext content plus tag and attachment lists as json text`() }

        test("toDomain reads json encoded tag and attachment lists") { `toDomain reads json encoded tag and attachment lists`() }

        test("toDomain keeps reading legacy csv text") { `toDomain keeps reading legacy csv text`() }

        test("trash memo entity uses the same list serialization strategy") { `trash memo entity uses the same list serialization strategy`() }
    }


    private fun `fromDomain stores plaintext content plus tag and attachment lists as json text`() {
        val memo =
            Memo(
                id = "memo-json",
                timestamp = 1L,
                updatedAt = 2L,
                content = "你好 memo",
                rawContent = "- 10:00 你好 memo",
                dateKey = "2026_04_19",
                tags = listOf("tag,with,comma", "travel"),
                imageUrls = listOf("folder,part/image.png", "voice_001.m4a"),
            )

        val entity = MemoEntity.fromDomain(memo)

        entity.tags shouldBe """["tag,with,comma","travel"]"""
        entity.imageUrls shouldBe """["folder,part/image.png","voice_001.m4a"]"""
        entity.content shouldBe memo.content
    }

    private fun `toDomain reads json encoded tag and attachment lists`() {
        val entity =
            MemoEntity(
                id = "memo-json",
                timestamp = 1L,
                updatedAt = 2L,
                content = "body",
                rawContent = "- 10:00 body",
                date = "2026_04_19",
                tags = """["tag,with,comma","travel"]""",
                imageUrls = """["folder,part/image.png","voice_001.m4a"]""",
            )

        val memo = entity.toDomain()

        memo.tags shouldBe listOf("tag,with,comma", "travel")
        memo.imageUrls shouldBe listOf("folder,part/image.png", "voice_001.m4a")
    }

    private fun `toDomain keeps reading legacy csv text`() {
        val entity =
            MemoEntity(
                id = "memo-csv",
                timestamp = 1L,
                updatedAt = 2L,
                content = "body",
                rawContent = "- 10:00 body",
                date = "2026_04_19",
                tags = "work,travel",
                imageUrls = "first.png,second.png",
            )

        val memo = entity.toDomain()

        memo.tags shouldBe listOf("work", "travel")
        memo.imageUrls shouldBe listOf("first.png", "second.png")
    }

    private fun `trash memo entity uses the same list serialization strategy`() {
        val memo =
            Memo(
                id = "trash-json",
                timestamp = 1L,
                updatedAt = 2L,
                content = "body",
                rawContent = "- 10:00 body",
                dateKey = "2026_04_19",
                tags = listOf("tag,with,comma"),
                imageUrls = listOf("folder,part/image.png"),
                isDeleted = true,
            )

        val entity = TrashMemoEntity.fromDomain(memo)
        val restored = entity.toDomain()

        entity.tags shouldBe """["tag,with,comma"]"""
        entity.imageUrls shouldBe """["folder,part/image.png"]"""
        restored.tags shouldBe listOf("tag,with,comma")
        restored.imageUrls shouldBe listOf("folder,part/image.png")
    }
}
