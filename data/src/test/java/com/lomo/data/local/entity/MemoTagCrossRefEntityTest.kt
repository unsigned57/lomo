package com.lomo.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: MemoEntity.toTagCrossRefs() extension.
 * - Behavior focus: decode stored tag list into individual cross-ref rows for sidebar and tag-filter queries.
 * - Observable outcomes: resulting MemoTagCrossRefEntity list content (tag values, de-duplication, emptiness).
 * - Red phase: before the fix, JSON-encoded tag strings like ["你好"] collapse into a single ref with tag literal
 *   `["你好"]`, so the sidebar renders the raw JSON string as a tag name.
 * - Excludes: Room DAO behavior, UI rendering, memo domain mapping.
 */
class MemoTagCrossRefEntityTest {
    @Test
    fun `toTagCrossRefs decodes json encoded tag list into separate refs`() {
        val entity = sampleEntity(tags = """["work","travel"]""")

        val refs = entity.toTagCrossRefs()

        assertEquals(
            listOf(
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "work"),
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "travel"),
            ),
            refs,
        )
    }

    @Test
    fun `toTagCrossRefs preserves tag value containing comma inside json encoding`() {
        val entity = sampleEntity(tags = """["tag,with,comma","travel"]""")

        val refs = entity.toTagCrossRefs()

        assertEquals(
            listOf(
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "tag,with,comma"),
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "travel"),
            ),
            refs,
        )
    }

    @Test
    fun `toTagCrossRefs decodes single cjk tag without exposing json brackets`() {
        val entity = sampleEntity(tags = """["你好"]""")

        val refs = entity.toTagCrossRefs()

        assertEquals(
            listOf(MemoTagCrossRefEntity(memoId = "memo-json", tag = "你好")),
            refs,
        )
    }

    @Test
    fun `toTagCrossRefs keeps reading legacy csv format`() {
        val entity = sampleEntity(tags = "work,travel")

        val refs = entity.toTagCrossRefs()

        assertEquals(
            listOf(
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "work"),
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "travel"),
            ),
            refs,
        )
    }

    @Test
    fun `toTagCrossRefs returns empty list when tags is empty string`() {
        val entity = sampleEntity(tags = "")

        val refs = entity.toTagCrossRefs()

        assertEquals(emptyList<MemoTagCrossRefEntity>(), refs)
    }

    @Test
    fun `toTagCrossRefs deduplicates repeated tag entries`() {
        val entity = sampleEntity(tags = """["work","work","travel"]""")

        val refs = entity.toTagCrossRefs()

        assertEquals(
            listOf(
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "work"),
                MemoTagCrossRefEntity(memoId = "memo-json", tag = "travel"),
            ),
            refs,
        )
    }

    private fun sampleEntity(tags: String): MemoEntity =
        MemoEntity(
            id = "memo-json",
            timestamp = 1L,
            updatedAt = 2L,
            content = "body",
            rawContent = "- 10:00 body",
            date = "2026_04_19",
            tags = tags,
            imageUrls = "",
        )
}
