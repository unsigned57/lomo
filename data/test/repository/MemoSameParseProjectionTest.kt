package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: MemoSavePlanFactory + MemoWorkspaceProjector same-parse analysis reuse.
 * - Owning layer: data adapter over lomo-workspace owner.
 * - Capability: free-content save analysis and scan-projected refresh analysis come from one owner
 *   parse each; save plan reuses contentAnalysis without a second renderMarkdown.
 *
 * Scenarios:
 * - Given free-floating body with tags/task/url/attachment, when create save plan runs, then one
 *   analyze projects tags, attachments, hasTodo, hasUrl and plan.contentAnalysis matches memo fields.
 * - Given a scan summary with hasTodo/hasUrl/tags/attachments, when projector builds entities, then
 *   Room flags match summary facts without requiring a second body render.
 *
 * Observable outcomes: MemoSavePlan.contentAnalysis, memo tags/imageUrls, entity hasTodo/hasUrl.
 * TDD proof: fails if save plan omits contentAnalysis or projector re-derives analysis from body text.
 * Excludes: Room I/O, file write, UI.
 */

import com.lomo.data.engine.WorkspaceMemoSummarySnapshot
import com.lomo.data.local.entity.decodeStoredMemoStringList
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.fakes.FakeWorkspaceMarkdownOwner
import com.lomo.data.testing.fakes.fakeMarkdownWorkspaceContentProjector
import com.lomo.data.testing.fakes.testWorkspaceMemoSummary
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import com.lomo.domain.usecase.MemoIdentityPolicy
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.time.ZoneId

class MemoSameParseProjectionTest : DataFunSpec() {
    init {
        test("save plan reuses one owner analysis for tags attachments and contentAnalysis") {
            val factory =
                MemoSavePlanFactory(
                    fakeMarkdownWorkspaceContentProjector(),
                    MemoIdentityPolicy(),
                )
            val content = "Ship #release with ![cover](img.png) and [ ] task https://lomo.app"
            val timestamp =
                LocalDateTime
                    .of(2026, 3, 27, 9, 15, 30)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

            val plan =
                factory.create(
                    content = content,
                    timestamp = timestamp,
                    filenameFormat = StorageFilenameFormats.DEFAULT_PATTERN,
                    timestampFormat = StorageTimestampFormats.DEFAULT_PATTERN,
                    existingFileContent = "",
                    precomputedSameTimestampCount = 0,
                )

            plan.memo.tags shouldBe plan.contentAnalysis.tags
            plan.memo.imageUrls shouldBe
                (plan.contentAnalysis.imageUrls + plan.contentAnalysis.audioUrls).distinct()
            plan.contentAnalysis.tags shouldBe listOf("release")
            plan.contentAnalysis.imageUrls shouldBe listOf("img.png")
            plan.contentAnalysis.hasTodo shouldBe true
            plan.contentAnalysis.hasUrl shouldBe true
            plan.contentAnalysis.hasAttachment shouldBe true
        }

        test("workspace projector stores scan summary analysis without second body render") {
            val owner = FakeWorkspaceMarkdownOwner()
            owner.seedMemo(
                rootPath = null,
                summary =
                    testWorkspaceMemoSummary(
                        path = "2026_03_27.md",
                        identity = "2026_03_27_09:15:30_0",
                        timePart = "09:15:30",
                        content = "body with #tag and task",
                        tags = listOf("tag"),
                        attachments = listOf("a.png"),
                        hasTodo = true,
                        hasUrl = true,
                    ),
            )
            val projector = MemoWorkspaceProjector(owner)
            val changeSet =
                projector.projectShard(
                    directory = MemoDirectoryType.MAIN,
                    metadata =
                        FileMetadataWithId(
                            filename = "2026_03_27.md",
                            lastModified = 1_700_000_000_000,
                            documentId = "doc-2026_03_27",
                            uriString = null,
                        ),
                )

            val entity = changeSet!!.let { it as MemoProjectionChangeSet.Active }.memos.single()
            entity.hasTodo shouldBe true
            entity.hasUrl shouldBe true
            entity.hasAttachment shouldBe true
            decodeStoredMemoStringList(entity.tags) shouldBe listOf("tag")
            decodeStoredMemoStringList(entity.imageUrls) shouldBe listOf("a.png")
        }
    }
}
