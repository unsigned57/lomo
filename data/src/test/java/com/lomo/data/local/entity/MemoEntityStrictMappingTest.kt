package com.lomo.data.local.entity

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId

/*
 * Behavior Contract:
 * - Unit under test: MemoEntity.toDomain and TrashMemoEntity.toDomain.
 * - Owning layer: data/local entity.
 * - Priority tier: P1.
 * - Capability: map persisted projection rows to domain models without historical repair or hidden
 *   projection recovery on the read path.
 *
 * Scenarios:
 * - Given a stored active row whose rawContent contains a different storage header/body, when read,
 *   then the domain memo exposes the persisted content and timestamp exactly.
 * - Given a stored active row with blank content and nonblank rawContent, when read, then blank
 *   content remains observable instead of being repaired from rawContent.
 * - Given a stored trash row, when read, then persisted projection fields are mapped strictly and
 *   the deleted lifecycle flag is explicit.
 *
 * Observable outcomes:
 * - domain timestamp, content, rawContent, tags, imageUrls, dateKey, localDate, and isDeleted.
 *
 * TDD proof:
 * - RED before the projection single-source fix because MemoEntity.toDomain recovered timestamp and
 *   content from rawContent on every read, hiding stale write/backfill projection state.
 *
 * Excludes:
 * - migration/backfill recovery, Room queries, markdown parsing, and UI rendering.
 */
class MemoEntityStrictMappingTest : DataFunSpec() {
    init {
        test("given stale raw storage header when active row is read then persisted content and timestamp are returned") {
            val persistedTimestamp = midnightTimestampOf(2022, 8, 18)
            val rawContent =
                """
                - 21:00:33 recovered body
                second line
                """.trimIndent()
            val entity =
                MemoEntity(
                    id = "memo-stale",
                    timestamp = persistedTimestamp,
                    updatedAt = persistedTimestamp + 1L,
                    content = "- 00:00 stale persisted content",
                    searchContent = "stale persisted content",
                    rawContent = rawContent,
                    date = "2022_08_18",
                    tags = """["persisted"]""",
                    imageUrls = """["persisted.png"]""",
                )

            val domain = entity.toDomain()

            domain.timestamp shouldBe persistedTimestamp
            domain.updatedAt shouldBe persistedTimestamp + 1L
            domain.content shouldBe "- 00:00 stale persisted content"
            domain.rawContent shouldBe rawContent
            domain.tags shouldBe listOf("persisted")
            domain.imageUrls shouldBe listOf("persisted.png")
            domain.dateKey shouldBe "2022_08_18"
            domain.localDate shouldBe LocalDate.of(2022, 8, 18)
            domain.isDeleted shouldBe false
        }

        test("given blank stored content when active row is read then rawContent is not used as fallback") {
            val entity =
                MemoEntity(
                    id = "memo-blank",
                    timestamp = midnightTimestampOf(2026, 3, 25),
                    content = "",
                    searchContent = "",
                    rawContent = "https://example.com/url-only",
                    date = "2026_03_25",
                    tags = "",
                    imageUrls = "",
                )

            val domain = entity.toDomain()

            domain.content shouldBe ""
            domain.rawContent shouldBe "https://example.com/url-only"
            domain.timestamp shouldBe midnightTimestampOf(2026, 3, 25)
        }

        test("given trash row when read then persisted projection is mapped with deleted lifecycle state") {
            val entity =
                TrashMemoEntity(
                    id = "trash-strict",
                    timestamp = 1L,
                    updatedAt = 2L,
                    content = "persisted trash body",
                    rawContent = "- 10:00 recovered trash body",
                    date = "2026_04_19",
                    tags = """["trash"]""",
                    imageUrls = """["trash.png"]""",
                )

            val domain = entity.toDomain()

            domain.timestamp shouldBe 1L
            domain.updatedAt shouldBe 2L
            domain.content shouldBe "persisted trash body"
            domain.rawContent shouldBe "- 10:00 recovered trash body"
            domain.tags shouldBe listOf("trash")
            domain.imageUrls shouldBe listOf("trash.png")
            domain.isDeleted shouldBe true
        }
    }

    private fun midnightTimestampOf(
        year: Int,
        month: Int,
        day: Int,
    ): Long =
        LocalDate
            .of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
