/*
 * Behavior Contract:
 * - Unit under test: ConflictTextDiffPresentation
 * - Owning layer: ui-components
 * - Priority tier: P0
 * - Capability: map bounded domain text-diff results into conflict dialog
 *   presentation states without asking Compose to render an unbounded diff.
 *
 * Scenarios:
 * - Given a computed diff has visible hunks, when presentation is resolved,
 *   then the dialog receives those hunks for DiffViewer.
 * - Given a computed diff has no visible hunks, when presentation is resolved,
 *   then the dialog receives the existing no-differences empty state.
 * - Given the domain diff reports TooLarge, when presentation is resolved,
 *   then the dialog receives the too-large fallback state instead of hunks.
 * - Given localized fallback text is needed, when resources are inspected, then
 *   both supported locales provide a user-visible too-large message.
 *
 * Observable outcomes:
 * - ConflictTextDiffPresentation values and localized resource entries.
 *
 * TDD proof:
 * - Presentation mapping: RED before implementation because
 *   resolveConflictTextDiffPresentation and ConflictTextDiffPresentation do
 *   not exist.
 * - Resource fallback: retained guard fails if either supported locale loses
 *   the too-large message while the UI can still produce that state.
 *
 * Excludes:
 * - Compose rendering, horizontal scrolling, DiffViewer row styling, and domain
 *   LCS correctness.
 */
package com.lomo.ui.component.dialog

import com.lomo.domain.model.SimpleLineDiff
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ConflictTextDiffPresentationTest : UiComponentsFunSpec() {
    init {
        test("given computed diff has hunks when presentation resolved then diff hunks are retained") {
            val hunk =
                SimpleLineDiff.DiffHunk(
                    lines =
                        listOf(
                            SimpleLineDiff.DiffLine(
                                op = SimpleLineDiff.DiffOp.INSERT,
                                text = "remote",
                                newLineNumber = 1,
                            ),
                        ),
                )

            val presentation =
                resolveConflictTextDiffPresentation(SimpleLineDiff.DiffResult.Computed(listOf(hunk)))

            presentation.shouldBeInstanceOf<ConflictTextDiffPresentation.Diff>().hunks shouldBe listOf(hunk)
        }

        test("given computed diff has no hunks when presentation resolved then empty state is returned") {
            val presentation =
                resolveConflictTextDiffPresentation(SimpleLineDiff.DiffResult.Computed(emptyList()))

            presentation shouldBe ConflictTextDiffPresentation.NoTextDiffs
        }

        test("given domain diff is too large when presentation resolved then too large state is returned") {
            val presentation =
                resolveConflictTextDiffPresentation(
                    SimpleLineDiff.DiffResult.TooLarge(
                        oldLineCount = 501,
                        newLineCount = 501,
                        maxMatrixCells = 250_000,
                    ),
                )

            presentation shouldBe ConflictTextDiffPresentation.TooLarge
        }

        test("given too large fallback when resources inspected then each supported locale has text") {
            assertSoftly {
                conflictStringResource(
                    localeDirectory = "values",
                    name = "sync_conflict_text_diff_too_large",
                ) shouldBe "Text diff is too large to display."
                conflictStringResource(
                    localeDirectory = "values-zh-rCN",
                    name = "sync_conflict_text_diff_too_large",
                ) shouldBe "\u6587\u672c\u5dee\u5f02\u8fc7\u5927\uff0c\u65e0\u6cd5\u663e\u793a\u3002"
            }
        }
    }

    private fun conflictStringResource(
        localeDirectory: String,
        name: String,
    ): String {
        val file = File("src/main/res/$localeDirectory/strings.xml")
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length)
            .asSequence()
            .map { index -> strings.item(index) }
            .first { node -> node.attributes.getNamedItem("name")?.nodeValue == name }
            .textContent
    }
}
