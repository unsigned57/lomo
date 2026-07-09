package com.lomo.data.local.projection

import com.lomo.data.local.entity.MemoImageAttachmentEntity
import com.lomo.data.local.entity.MemoTagCrossRefEntity
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.util.SearchTokenizer
import com.lomo.domain.model.Memo
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoProjectionProjector
 * - Owning layer: data/local projection.
 * - Priority tier: P1.
 * - Capability: produce every persisted memo projection from one canonical data-layer path while
 *   using domain content analysis for business content semantics.
 *
 * Scenarios:
 * - Given a domain memo whose legacy tag/image fields disagree with content, when projected for
 *   active storage, then entity JSON, search text, tag refs, and image refs are generated from the
 *   same content projection.
 * - Given content that exercises analyzer-only todo, attachment, and URL semantics, when projected
 *   for active storage, then the persisted content-flag columns match MemoContentAnalyzer output.
 * - Given the same memo is projected for trash storage, when references are requested, then trash
 *   entity image refs use the same canonical image projection.
 *
 * Observable outcomes:
 * - MemoEntity/TrashMemoEntity fields plus MemoTagCrossRefEntity and MemoImageAttachmentEntity
 *   values.
 *
 * TDD proof:
 * - Fails before implementation because MemoProjectionProjector does not exist and projection
 *   creation is split across entity factories and DAO extension helpers.
 * - Fails before the content-flag projection fix because MemoEntity has no persisted hasTodo,
 *   hasAttachment, or hasUrl fields populated by MemoProjectionProjector.
 *
 * Excludes:
 * - Room SQL execution, file I/O, UI markdown rendering, and remote sync.
 */
class MemoProjectionProjectorTest : DataFunSpec() {
    init {
        test("given stale domain lists when active memo is projected then all persisted projections come from content") {
            val memo =
                sampleMemo(
                    content =
                        """
                        Review #work #work
                        ![cover](images/a.png)
                        ![[camera.jpg]]
                        [voice](voice_001.m4a)
                        """.trimIndent(),
                    tags = listOf("stale"),
                    imageUrls = listOf("stale.png"),
                )

            val projection = MemoProjectionProjector.projectActive(memo)

            assertSoftly(projection) {
                entity.tags shouldBe """["work"]"""
                entity.imageUrls shouldBe """["images/a.png","camera.jpg","voice_001.m4a"]"""
                entity.searchContent shouldBe SearchTokenizer.tokenize(memo.content)
                entity.hasTodo shouldBe false
                entity.hasAttachment shouldBe true
                entity.hasUrl shouldBe false
                tagRefs shouldBe listOf(MemoTagCrossRefEntity(memoId = memo.id, tag = "work"))
                imageRefs shouldBe
                    listOf(
                        MemoImageAttachmentEntity(memoId = memo.id, imagePath = "images/a.png"),
                        MemoImageAttachmentEntity(memoId = memo.id, imagePath = "camera.jpg"),
                        MemoImageAttachmentEntity(memoId = memo.id, imagePath = "voice_001.m4a"),
                    )
            }
        }

        test("given analyzer-only syntax when active memo is projected then content flags are persisted") {
            val memo =
                sampleMemo(
                    content =
                        """
                          -	[x] indented task
                        ![[diagram.png]]
                        [meeting audio](voice_001.m4a)
                        geo:31.2304,121.4737
                        mailto:team@example.com
                        """.trimIndent(),
                    tags = emptyList(),
                    imageUrls = emptyList(),
                )

            val projection = MemoProjectionProjector.projectActive(memo)

            assertSoftly(projection.entity) {
                hasTodo shouldBe true
                hasAttachment shouldBe true
                hasUrl shouldBe true
            }
        }

        test("given stale domain lists when trash memo is projected then trash image refs come from content") {
            val memo =
                sampleMemo(
                    content = "#trash ![kept](kept.png) [audio](kept.m4a)",
                    tags = listOf("stale"),
                    imageUrls = listOf("stale.png"),
                ).copy(isDeleted = true)

            val projection = MemoProjectionProjector.projectTrash(memo)

            assertSoftly(projection) {
                entity.tags shouldBe """["trash"]"""
                entity.imageUrls shouldBe """["kept.png","kept.m4a"]"""
                imageRefs shouldBe
                    listOf(
                        MemoImageAttachmentEntity(memoId = memo.id, imagePath = "kept.png"),
                        MemoImageAttachmentEntity(memoId = memo.id, imagePath = "kept.m4a"),
                    )
            }
        }
    }

    private fun sampleMemo(
        content: String,
        tags: List<String>,
        imageUrls: List<String>,
    ): Memo =
        Memo(
            id = "memo-1",
            timestamp = 1L,
            updatedAt = 2L,
            content = content,
            rawContent = "- 10:00 $content",
            dateKey = "2026_04_19",
            tags = tags,
            imageUrls = imageUrls,
        )
}
