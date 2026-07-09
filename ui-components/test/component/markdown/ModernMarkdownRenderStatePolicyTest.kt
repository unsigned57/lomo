package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toImmutableList

/*
 * Test Contract:
 * - Unit under test: modern markdown render-state policy.
 * - Behavior focus: memo markdown must keep visible fallback content while an async render plan is still unavailable, and must switch to the limited ready plan once planning completes.
 * - Observable outcomes: resolved render-state kind, fallback text payload, and ready-plan visible item count.
 * - Red phase: Fails before the fix because the renderer returns no content while waiting for an async plan, so fast-scrolling memo items can appear blank before the render plan arrives.
 * - Excludes: Compose composition timing, Android TextView behavior, and third-party markdown parser internals beyond the already-tested render plan.
 */
class ModernMarkdownRenderStatePolicyTest : UiComponentsFunSpec() {
    init {
        test("pending render state exposes visible fallback text instead of a blank frame") {
        val state =
            resolveModernMarkdownRenderState(
                basePlan = null,
                content = "body #todo line",
                maxVisibleBlocks = 3,
                knownTagsToStrip = listOf("todo").toImmutableList(),
            )

        val pending = state.shouldBeInstanceOf<ModernMarkdownRenderState.Pending>()
        (pending.fallbackText) shouldBe ("body line")
        }
    }

    init {
        test("ready render state keeps the visible block limit from the computed plan") {
        val plan =
            createModernMarkdownRenderPlan(
                content =
                    """
                    one

                    two

                    three
                    """.trimIndent(),
                knownTagsToStrip = emptyList<String>().toImmutableList(),
            )

        val state =
            resolveModernMarkdownRenderState(
                basePlan = plan,
                content = plan.content,
                maxVisibleBlocks = 2,
                knownTagsToStrip = emptyList<String>().toImmutableList(),
            )

        val ready = state.shouldBeInstanceOf<ModernMarkdownRenderState.Ready>()
        (ready.plan.totalBlocks) shouldBe (3)
        (ready.plan.items.size) shouldBe (2)
        }
    }
}
