package com.lomo.ui.component.card

/*
 * Behavior Contract:
 * - Unit under test: MemoCardHeightCache
 * - Owning layer: ui-components
 * - Priority tier: P1
 * - Capability: remember and look up measured memo card body heights keyed by content, render
 *   document byte length, presentation plan, density, and font scale while evicting least-recently
 *   used entries beyond the 256-entry capacity.
 */

import com.lomo.domain.model.markdown.MarkdownRenderDocument
import com.lomo.ui.component.markdown.MarkdownIrPresentationPlan
import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

class MemoCardHeightCacheTest : UiComponentsFunSpec() {
    init {
        beforeTest {
            MemoCardHeightCache.clear()
        }

        fun document(text: String) = MarkdownRenderDocument(
            sourceByteLength = text.encodeToByteArray().size.toULong(),
            plainText = text,
            tagNames = emptyList(),
            attachments = emptyList(),
            blocks = emptyList(),
        )

        test("stores and returns measured height per content and scale") {
            val content = "Line 1\nLine 2\nLine 3"
            val doc = document(content)
            val plan = MarkdownIrPresentationPlan(totalBlocks = 1, items = emptyList())
            val key1x = MemoCardHeightCache.keyFor(content, doc, plan, density = 2.0f, fontScale = 1.0f)
            val keyScaled = MemoCardHeightCache.keyFor(content, doc, plan, density = 2.0f, fontScale = 1.3f)

            MemoCardHeightCache.put(key1x, 840)

            MemoCardHeightCache.get(key1x) shouldBe 840
            MemoCardHeightCache.get(keyScaled) shouldBe null
        }

        test("ignores non-positive measurements and evicts oldest beyond 256 entries") {
            val baseDoc = document("sample")
            val zeroKey = MemoCardHeightCache.keyFor("sample", baseDoc, null, density = 1.0f, fontScale = 1.0f)
            MemoCardHeightCache.put(zeroKey, 0)
            MemoCardHeightCache.get(zeroKey) shouldBe null

            val keys = (0..260).map { idx ->
                val text = "memo-$idx"
                val key = MemoCardHeightCache.keyFor(text, document(text), null, density = 1.0f, fontScale = 1.0f)
                MemoCardHeightCache.put(key, 100 + idx)
                key
            }

            MemoCardHeightCache.get(keys.first()) shouldBe null
            MemoCardHeightCache.get(keys.last()) shouldBe 360
        }
    }
}
