package com.lomo.data.local.entity

import com.lomo.data.local.projection.MemoProjectionProjector
import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoProjectionProjector plus MemoEntity/TrashMemoEntity.toDomain
 * - Owning layer: data/local entity.
 * - Priority tier: P0.
 * - Capability: serialize canonical memo projections for storage/query and strictly map stored rows back to domain.
 *
 * Scenarios:
 * - Given a memo whose stored tag/attachment lists disagree with content, when projected, then stored list
 *   fields are JSON encoded from content and visible content remains plaintext.
 * - Given JSON or legacy CSV stored list fields, when deserialized, then domain tag/image lists are
 *   restored.
 * - Given a deleted memo, when projected for trash storage, then trash entity list fields follow the
 *   same canonical content projection.
 * Observable outcomes:
 * - persisted content text, encoded tag/image fields, and decoded domain lists.
 *
 * TDD proof:
 * - RED before the projection fix because entity factories and cross-ref helpers could derive
 *   serialized fields from stale domain lists independently from write-path search/ref projection.
 *
 * Excludes:
 * - FTS table writes, Room query execution, stale-content recovery, and UI rendering.
 */
class MemoEntitySerializationTest : DataFunSpec() {
    init {
        test("projector stores plaintext content plus canonical tag and attachment lists as json text") {
            `projector stores plaintext content plus canonical tag and attachment lists as json text`()
        }

        test("toDomain reads json encoded tag and attachment lists") { `toDomain reads json encoded tag and attachment lists`() }

        test("toDomain keeps reading legacy csv text") { `toDomain keeps reading legacy csv text`() }

        test("trash memo entity uses the same list serialization strategy") { `trash memo entity uses the same list serialization strategy`() }
    }


    private fun `projector stores plaintext content plus canonical tag and attachment lists as json text`() {
        val memo =
            Memo(
                id = "memo-json",
                timestamp = 1L,
                updatedAt = 2L,
                content = "你好 memo #travel\n![cover](folder,part/image.png)\n[voice](voice_001.m4a)",
                rawContent = "- 10:00 你好 memo #travel\n![cover](folder,part/image.png)\n[voice](voice_001.m4a)",
                dateKey = "2026_04_19",
                tags = listOf("stale"),
                imageUrls = listOf("stale.png"),
            )

        val entity = MemoProjectionProjector.projectActive(memo).entity

        entity.tags shouldBe """["travel"]"""
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
                searchContent = "body",
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
                searchContent = "body",
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
                content = "#travel ![cover](folder,part/image.png)",
                rawContent = "- 10:00 #travel ![cover](folder,part/image.png)",
                dateKey = "2026_04_19",
                tags = listOf("stale"),
                imageUrls = listOf("stale.png"),
                isDeleted = true,
            )

        val entity = MemoProjectionProjector.projectTrash(memo).entity
        val restored = entity.toDomain()

        entity.tags shouldBe """["travel"]"""
        entity.imageUrls shouldBe """["folder,part/image.png"]"""
        restored.tags shouldBe listOf("travel")
        restored.imageUrls shouldBe listOf("folder,part/image.png")
    }
}
