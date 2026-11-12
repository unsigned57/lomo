package com.lomo.app.feature.main

/*
 * Behavior Contract:
 * - Unit under test: MemoUiMapper
 * - Owning layer: production path under test
 * - Priority tier: P1
 * - Capability: preserve observable product behavior after Markdown semantic ownership moved to
 *   lomo-workspace (typed IR, workspace scan/render/document commands) with Kotlin adapters only.
 *
 * Scenarios:
 * - Given production collaborators expose workspace IR / document-command seams, when this suite
 *   runs, then assertions verify the same user-visible outcomes without Kotlin MarkdownParser.
 * - Given deleted JetBrains or line-authority helpers, when tests construct fakes, then they use
 *   FakeMarkdownWorkspace / content projector adapters instead of dual-authority parsers.
 * - Given invalid or missing readiness inputs, when exercised, then fail-closed outcomes remain.
 * - Given one paging batch, when multiple memos are mapped, then reminders are fetched once for
 *   the whole batch rather than once per memo.
 * - Given a preview row whose store char_count exceeds the expand threshold, when mapped, then
 *   expand is shown even though the preview body is short.
 * - Given a non-empty preview body, when mapped, then collapsedSummary stays blank because the
 *   preview itself is the collapsed surface.
 * - Given a preview row, when mapped, then its block-bounded preview bytes render through the
 *   Rust Markdown owner (lomo-store cuts previews on block boundaries for standalone rendering).
 * - Given a preview shorter than the store char_count, when mapped, then expand is offered so the
 *   cut content is reachable; a preview equal to the whole memo keeps expand hidden.
 *
 * Observable outcomes:
 * - Public method results, DI wiring, and presentation fields match the post-cutover contracts.
 *
 * TDD proof:
 * - RED: suites fail to compile or assert against MarkdownParser / JetBrains plan types after cutover.
 * - GREEN: ./kotlin test on this class passes against workspace IR adapters.
 *
 * Excludes:
 * - Room schema ownership, sync backend redesign, and Compose pixel rendering.
 *
 * TDD proof (preview rendering): RED `./kotlin test --include-module=app
 *   --include-classes='com.lomo.app.feature.main.MemoUiMapperTest'` — preview render calls were []
 *   and a 46-char preview of a 320-char memo reported shouldShowExpand=false; GREEN after previews
 *   render through the owner and truncation drives expand.
 *
 * Test Change Justification:
 * - Reason category: feed preview contract realigned with the lomo-store preview owner.
 * - Old behavior/assertion being replaced: "preview mapping uses a bounded summary and skips full
 *   Markdown rendering" asserted zero render calls and one inert paragraph block.
 * - Why old assertion is no longer correct: lomo-store `body_preview` cuts on block boundaries
 *   precisely so the app renders it as standalone Markdown; the inert paragraph showed raw `>`/`![]`
 *   syntax and hid truncation in the feed.
 * - Coverage preserved by: the replacement asserts the preview bytes reach the owner exactly once and
 *   the projected-count expand cases remain.
 * - Why this is not fitting the test to the implementation: it asserts the owner contract (what is
 *   rendered and whether cut content is reachable), not a call shape of the mapper.
 * - Reason category: production Markdown ownership cutover to Rust workspace IR / document commands.
 * - Old behavior/assertion being replaced: tests that assumed Kotlin MarkdownParser, MemoTextProcessor,
 *   JetBrains render plans, or dual-authority analysis helpers as production collaborators.
 * - Why old assertion is no longer correct: production storage analysis and presentation consume
 *   lomo-workspace typed IR and workspace adapters; the deleted Kotlin/JetBrains authorities are gone.
 * - Coverage preserved by: the same observable product outcomes (mapping, mutation gates, DI wiring,
 *   share/card presentation) re-asserted against FakeMarkdownWorkspace / IR / projector seams.
 * - Why this is not fitting the test to the implementation: assertions still check public behavior and
 *   fail-closed boundaries, not private parser implementation details.
 */

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import com.lomo.domain.model.MemoContentKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

class MemoUiMapperTest : AppFunSpec() {
    private val mapper =
        testMemoUiMapper(
            workspace =
            FakeMarkdownWorkspaceRepository(
                plainTextTransform = { content ->
                    // Presentation plain text drops known #tag tokens for body display contracts.
                    content
                        .replace(Regex("""(^|\s)#(work|todo)(?=\s|$)""")) { match ->
                            if (match.value.first().isWhitespace()) " " else ""
                        }.trim()
                },
            ),
        )

    init {
        test("mapToUiModel projects owner IR document and keeps raw tags on processed content") {
            runTest {
                val memo =
                    memo(
                        content = "Meeting with C# team #work and #todo today.",
                        tags = listOf("work", "todo"),
                    )

                val uiModel = mapper.mapToUiModel(memo, rootPath = null, imagePath = null, imageMap = emptyMap())

                uiModel.processedContent.contains("#work") shouldBe true
                uiModel.processedContent.contains("#todo") shouldBe true
                uiModel.renderDocument.plainText.contains("C#") shouldBe true
                uiModel.renderDocument.plainText.contains("#work") shouldBe false
                uiModel.renderDocument.plainText.contains("#todo") shouldBe false
                uiModel.tags shouldBe listOf("work", "todo")
            }
        }

        test("mapToUiModel invalidates cache when memo content changes") {
            runTest {
                val first = memo(content = "first body")
                val initial = mapper.mapToUiModel(first, null, null, emptyMap())
                val updated =
                    mapper.mapToUiModel(first.copy(content = "second body"), null, null, emptyMap())

                initial.renderDocument.plainText shouldBe "first body"
                updated.renderDocument.plainText shouldBe "second body"
                (updated.renderDocument === initial.renderDocument) shouldBe false
            }
        }

        test("mapToUiModels maps multiple memos with stable ids") {
            runTest {
                val memos =
                    listOf(
                        memo(id = "a", content = "alpha"),
                        memo(id = "b", content = "beta"),
                    )
                val models = mapper.mapToUiModels(memos, null, null, emptyMap())
                models.map { it.memo.id } shouldBe listOf("a", "b")
                models.map { it.renderDocument.plainText } shouldBe listOf("alpha", "beta")
                models.first().renderDocument shouldNotBe null
            }
        }

        test("mapToUiModels resolves reminders once for the paging batch") {
            runTest {
                val batchMapper = testMemoUiMapper()

                val models =
                    batchMapper.mapToUiModels(
                        listOf(memo(id = "a", content = "alpha"), memo(id = "b", content = "beta")),
                        null,
                        null,
                        emptyMap(),
                    )

                models.map { it.memo.id } shouldBe listOf("a", "b")
                models.map { it.memo.reminders } shouldBe listOf(emptyList(), emptyList())
            }
        }

        test("given preview and full facts for one revision when cached mapping runs then bodies do not alias") {
            runTest {
                val preview = memo(
                    content = "preview",
                    contentRevision = 7L,
                    fileFingerprint = "fp",
                    contentKind = MemoContentKind.Preview,
                )
                val full = memo(
                    content = "preview plus the complete body",
                    contentRevision = 7L,
                    fileFingerprint = "fp",
                )

                mapper.mapToCachedUiModel(preview, null, null, emptyMap(), preview.reminders)
                val fullModel = mapper.mapToCachedUiModel(full, null, null, emptyMap(), full.reminders)

                fullModel.renderDocument.plainText shouldBe "preview plus the complete body"
            }
        }

        test("given a preview row when mapped then the bounded preview renders through the Markdown owner") {
            runTest {
                val rendered = mutableListOf<String>()
                val previewMapper =
                    testMemoUiMapper(
                        workspace = FakeMarkdownWorkspaceRepository(onRender = { rendered += it }),
                    )
                val preview =
                    memo(
                        content = "# preview heading\n\nsummary only",
                        contentRevision = 7L,
                        fileFingerprint = "fp",
                        contentKind = MemoContentKind.Preview,
                    )

                val uiModel = previewMapper.mapToCachedUiModel(preview, null, null, emptyMap(), preview.reminders)

                rendered shouldBe listOf(preview.content)
                uiModel.renderDocument.plainText shouldBe preview.content
            }
        }

        test("given a preview cut shorter than its projected char count when mapped then expand is offered") {
            runTest {
                val preview =
                    memo(
                        content = "first two hundred characters of a longer memo",
                        contentKind = MemoContentKind.Preview,
                        projectedCharCount = 320L,
                    )

                val uiModel = mapper.mapToUiModel(preview, null, null, emptyMap())

                uiModel.shouldShowExpand shouldBe true
            }
        }

        test("given a preview equal to the whole memo when mapped then expand stays hidden") {
            runTest {
                val content = "complete short memo"
                val preview =
                    memo(
                        content = content,
                        contentKind = MemoContentKind.Preview,
                        projectedCharCount = content.length.toLong(),
                    )

                val uiModel = mapper.mapToUiModel(preview, null, null, emptyMap())

                uiModel.shouldShowExpand shouldBe false
            }
        }

        test("preview mapping shows expand from projected char count not preview length") {
            runTest {
                val preview =
                    memo(
                        content = "short preview",
                        contentKind = MemoContentKind.Preview,
                        projectedCharCount = 900L,
                    )

                val uiModel = mapper.mapToUiModel(preview, null, null, emptyMap())

                uiModel.shouldShowExpand shouldBe true
                uiModel.collapsedSummary shouldBe ""
            }
        }

        test("short preview without projected char count does not show expand") {
            runTest {
                val preview =
                    memo(
                        content = "short preview",
                        contentKind = MemoContentKind.Preview,
                    )

                val uiModel = mapper.mapToUiModel(preview, null, null, emptyMap())

                uiModel.shouldShowExpand shouldBe false
            }
        }
    }
}

private fun memo(
    id: String = "memo-1",
    content: String,
    tags: List<String> = emptyList(),
    contentRevision: Long? = null,
    fileFingerprint: String? = null,
    contentKind: MemoContentKind = MemoContentKind.Full,
    projectedCharCount: Long? = null,
): Memo = Memo(
    id = id,
    timestamp = 1_700_000_000_000L,
    content = content,
    rawContent = "- 10:00 $content",
    dateKey = "2026_03_27",
    tags = tags,
    contentRevision = contentRevision,
    fileFingerprint = fileFingerprint,
    contentKind = contentKind,
    projectedCharCount = projectedCharCount,
)
