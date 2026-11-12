package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import com.lomo.app.testing.fakes.FakeMarkdownWorkspaceRepository
import com.lomo.app.testing.fakes.testMemoUiMapper
import com.lomo.domain.model.Memo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * Capability: metadata updates reuse bounded cached Markdown rendering; owner: app; P1.
 * Scenarios:
 * Given unchanged body/media inputs, when pin/pending/timestamp metadata changes, then the
 * returned row has current metadata and reuses its rendered document and presentation plan.
 * Given changed body, when mapped again, then the new body is rendered and published.
 * Observable outcomes: current row, rendered text, plan identity and render work count.
 * TDD proof: RED/GREEN in target/lomo/reports/manual/startup-scroll-round2/red.log.
 * Excludes: Rust Markdown grammar, image decoding and Compose frame timing.
 */
class MemoUiMapperRenderReuseTest : AppFunSpec() {
    init {
        test("given unchanged body when metadata changes then rendering is reused and metadata stays current") {
            runTest {
                val rendered = mutableListOf<String>()
                val mapper = testMemoUiMapper(FakeMarkdownWorkspaceRepository(onRender = rendered::add))
                val memo = Memo(
                    id = "memo",
                    timestamp = 1L,
                    content = "body",
                    rawContent = "body",
                    dateKey = "2026_10_28",
                )
                val before = mapper.peekOrMapToCachedUiModel(memo, null, null, emptyMap(), emptyList())
                val changed = memo.copy(isPinned = true, isPending = true, updatedAt = 2L)
                val after = mapper.peekOrMapToCachedUiModel(changed, null, null, emptyMap(), emptyList())

                after.memo shouldBe changed
                after.renderDocument.plainText shouldBe "body"
                after.renderDocument shouldBeSameInstanceAs before.renderDocument
                after.presentationPlan shouldBeSameInstanceAs before.presentationPlan
                rendered shouldBe listOf("body")
            }
        }

        test("given a cached body when the body changes then the new rendering replaces it") {
            runTest {
                val rendered = mutableListOf<String>()
                val mapper = testMemoUiMapper(FakeMarkdownWorkspaceRepository(onRender = rendered::add))
                val memo = Memo(
                    id = "memo",
                    timestamp = 1L,
                    content = "old",
                    rawContent = "old",
                    dateKey = "2026_10_28",
                )
                mapper.peekOrMapToCachedUiModel(memo, null, null, emptyMap(), emptyList())
                val changed = memo.copy(content = "new", rawContent = "new", updatedAt = 2L)
                val after = mapper.peekOrMapToCachedUiModel(changed, null, null, emptyMap(), emptyList())

                after.memo shouldBe changed
                after.renderDocument.plainText shouldBe "new"
                rendered shouldBe listOf("old", "new")
            }
        }
    }
}
