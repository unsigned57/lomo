package com.lomo.data.local.entity

import com.lomo.domain.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Test

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
class MemoEntitySerializationTest {
    @Test
    fun `fromDomain stores plaintext content plus tag and attachment lists as json text`() {
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

        assertEquals("""["tag,with,comma","travel"]""", entity.tags)
        assertEquals("""["folder,part/image.png","voice_001.m4a"]""", entity.imageUrls)
        assertEquals(memo.content, entity.content)
    }

    @Test
    fun `toDomain reads json encoded tag and attachment lists`() {
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

        assertEquals(listOf("tag,with,comma", "travel"), memo.tags)
        assertEquals(listOf("folder,part/image.png", "voice_001.m4a"), memo.imageUrls)
    }

    @Test
    fun `toDomain keeps reading legacy csv text`() {
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

        assertEquals(listOf("work", "travel"), memo.tags)
        assertEquals(listOf("first.png", "second.png"), memo.imageUrls)
    }

    @Test
    fun `trash memo entity uses the same list serialization strategy`() {
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

        assertEquals("""["tag,with,comma"]""", entity.tags)
        assertEquals("""["folder,part/image.png"]""", entity.imageUrls)
        assertEquals(listOf("tag,with,comma"), restored.tags)
        assertEquals(listOf("folder,part/image.png"), restored.imageUrls)
    }
}
