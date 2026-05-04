package com.lomo.ui.component.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: memo-card expand motion policy.
 * - Behavior focus: expandable memo cards use a single visual-state content transform so
 *   switching between collapsed preview and full markdown never relies on whole-body size
 *   interpolation, instant outer clipping, or paired visibility enter-exit blocks.
 * - Observable outcomes: resolved body transition mode for expandable vs non-expandable cards, and
 *   whether the body container may apply its own size animation; collapse size motion keeps a
 *   long-duration path instead of cutting directly to the collapsed body; expandable body content
 *   is owned by one AnimatedContent size transform without delayed content alpha fades; collapse
 *   keeps expanded content visible until the size transform reaches its collapsed target; collapsed
 *   height caps and overlays belong to collapsed visual-state content, not the outer body container.
 * - Red phase: Fails before the fix because the body has no visual-state resolver, the outer
 *   memo container still clips/overlays collapsed content, and the memo body size motion still
 *   resolves to the shorter legacy duration; the collapse content-switch regression fails before
 *   this fix because no policy keeps expanded content visible until the size transform reaches
 *   the collapsed target.
 * - Excludes: Compose animation runtime behavior, pixel rendering, and markdown parsing.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression boundary correction.
 * - Old behavior/assertion being replaced: expandable memo body transitions required an
 *   expressive fade-through timing where outgoing content faded before incoming content appeared.
 * - Why the old assertion is no longer correct: the reported flicker is caused by stacking that
 *   content alpha transition on top of the height transform and LazyList resize session. The
 *   body now needs a size-only transition with an instant content-state swap.
 * - Coverage preserved by: the size-duration assertions still require the long emphasized height
 *   path, and the AnimatedContent/SizeTransform source contract still requires a single owner for
 *   expandable body size changes.
 * - Why this is not fitting the test to the implementation: the new assertion locks the
 *   user-visible no-flicker contract: memo body content must not disappear and reappear while the
 *   row height is already animating.
 */
class MemoCardExpandMotionPolicyTest {
    @Test
    fun `expandable memo cards use state content transform transition`() {
        assertEquals(
            MemoCardBodyTransitionMode.StateContentTransform,
            resolveMemoCardBodyTransitionMode(shouldShowExpand = true),
        )
    }

    @Test
    fun `non-expandable memo cards keep snap body mode`() {
        assertEquals(
            MemoCardBodyTransitionMode.Snap,
            resolveMemoCardBodyTransitionMode(shouldShowExpand = false),
        )
    }

    @Test
    fun `state content transform owns expandable body height changes`() {
        assertEquals(
            MemoCardBodyContainerSizeAnimation.Disabled,
            resolveMemoCardBodyContainerSizeAnimation(MemoCardBodyTransitionMode.StateContentTransform),
        )
    }

    @Test
    fun `state content transform collapse keeps long duration size motion`() {
        val spec = resolveMemoCardBodyMotionSpec(MemoCardBodyTransitionMode.StateContentTransform)

        assertEquals(500, spec.sizeEnterDurationMillis)
        assertEquals(500, spec.sizeExitDurationMillis)
    }

    @Test
    fun `state content transform uses size-only body transition without delayed alpha fade`() {
        val spec = resolveMemoCardBodyMotionSpec(MemoCardBodyTransitionMode.StateContentTransform)

        assertTrue(
            "Expandable body size should move in the long emphasized transition band.",
            spec.sizeEnterDurationMillis >= EXPRESSIVE_SIZE_TRANSITION_MIN_DURATION_MILLIS,
        )
        assertTrue(
            "Collapsing should keep the same calm size band as expanding.",
            spec.sizeExitDurationMillis >= EXPRESSIVE_SIZE_TRANSITION_MIN_DURATION_MILLIS,
        )
        assertEquals(
            "Expandable body content should not fade in after the size transition has started.",
            0,
            spec.fadeInDurationMillis,
        )
        assertEquals(
            "Outgoing body content should not fade out while the row is already resizing.",
            0,
            spec.fadeOutDurationMillis,
        )
        assertEquals(
            "Incoming body content must not be delayed behind a blank or stale frame.",
            0,
            spec.fadeInDelayMillis,
        )
    }

    @Test
    fun `collapsing swaps to collapsed content only after body size reaches collapsed target`() {
        val spec = resolveMemoCardBodyMotionSpec(MemoCardBodyTransitionMode.StateContentTransform)
        val switchSpec =
            resolveMemoCardBodyContentSwitchSpec(
                motionSpec = spec,
                isExpanding = false,
            )

        assertEquals(
            "Collapsed target content should appear only when the collapse size animation has finished.",
            spec.sizeExitDurationMillis,
            switchSpec.targetContentEnterDelayMillis,
        )
        assertEquals(
            "Expanded outgoing content should stay visible until the collapse size animation has finished.",
            spec.sizeExitDurationMillis,
            switchSpec.initialContentExitDelayMillis,
        )
        assertEquals(0, switchSpec.targetContentEnterDurationMillis)
        assertEquals(0, switchSpec.initialContentExitDurationMillis)
    }

    @Test
    fun `expanding swaps to full content immediately while body size grows`() {
        val spec = resolveMemoCardBodyMotionSpec(MemoCardBodyTransitionMode.StateContentTransform)
        val switchSpec =
            resolveMemoCardBodyContentSwitchSpec(
                motionSpec = spec,
                isExpanding = true,
            )

        assertEquals(0, switchSpec.targetContentEnterDelayMillis)
        assertEquals(0, switchSpec.initialContentExitDelayMillis)
        assertEquals(0, switchSpec.targetContentEnterDurationMillis)
        assertEquals(0, switchSpec.initialContentExitDurationMillis)
    }

    @Test
    fun `state content transform keeps collapsed markdown target while expanded`() {
        assertEquals(
            MemoCardCollapsedPreviewMode.MarkdownPreview,
            resolveMemoCardBodyCollapsedTargetPreviewMode(
                bodyTransitionMode = MemoCardBodyTransitionMode.StateContentTransform,
                currentPreviewMode = MemoCardCollapsedPreviewMode.FullContent,
                hasProcessedContent = true,
                collapsedSummary = "summary",
            ),
        )
    }

    @Test
    fun `state content transform declares a visual state resolver`() {
        val source = uiComponentsSourceFile("card/MemoCardBodyTransitionMode.kt").readText()

        assertTrue(source.contains("MemoCardBodyVisualState"))
        assertTrue(source.contains("resolveMemoCardBodyVisualState"))
        assertTrue(source.contains("Expanded"))
        assertTrue(source.contains("CollapsedSummary"))
        assertTrue(source.contains("CollapsedMarkdownPreview"))
    }

    @Test
    fun `snap body mode keeps current preview mode`() {
        assertEquals(
            MemoCardCollapsedPreviewMode.FullContent,
            resolveMemoCardBodyCollapsedTargetPreviewMode(
                bodyTransitionMode = MemoCardBodyTransitionMode.Snap,
                currentPreviewMode = MemoCardCollapsedPreviewMode.FullContent,
                hasProcessedContent = true,
                collapsedSummary = "summary",
            ),
        )
    }

    @Test
    fun `expandable body uses single animated content size transform`() {
        val source = uiComponentsSourceFile("card/MemoCardBodyContent.kt").readText()

        assertTrue(
            "Expandable memo body transitions should be owned by AnimatedContent.",
            source.contains("AnimatedContent("),
        )
        assertTrue(
            "Expandable memo body transitions should animate size with SizeTransform.",
            source.contains("SizeTransform("),
        )
        assertFalse(
            "Paired AnimatedVisibility blocks replay independent enter-exit height animations.",
            source.contains("AnimatedVisibility("),
        )
        assertFalse(
            "Expandable memo body should not expand incoming content from zero height.",
            source.contains("expandVertically("),
        )
        assertFalse(
            "Expandable memo body should not shrink outgoing content to zero height.",
            source.contains("shrinkVertically("),
        )
    }

    @Test
    fun `outer memo body container does not clip collapsed target before transition`() {
        val source = uiComponentsSourceFile("card/MemoCard.kt").readText()

        assertFalse(
            "Collapsed max-height must not be applied by the outer body container on the first collapse frame.",
            source.contains("if (isCollapsedPreview) base.heightIn(max = 240.dp) else base"),
        )
        assertFalse(
            "Collapsed overlay must be owned by collapsed visual-state content, not the outer body container.",
            source.contains("if (isCollapsedPreview) {\n            MemoCardCollapsedOverlay()"),
        )
    }

    @Test
    fun `collapsed visual content owns height cap and overlay`() {
        val source = uiComponentsSourceFile("card/MemoCardBodyContent.kt").readText()

        assertTrue(
            "Collapsed visual-state content should own the height cap.",
            source.contains("heightIn(max = COLLAPSED_BODY_MAX_HEIGHT)"),
        )
        assertTrue(
            "Collapsed visual-state content should own the overlay.",
            source.contains("MemoCardCollapsedOverlay()"),
        )
    }

    private fun uiComponentsSourceFile(relativePath: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidates =
            listOf(
                currentDir.resolve("src/main/java/com/lomo/ui/component/$relativePath"),
                currentDir.resolve("ui-components/src/main/java/com/lomo/ui/component/$relativePath"),
            )
        return checkNotNull(candidates.firstOrNull(File::exists)) {
            "Failed to resolve ui-components source file $relativePath from ${currentDir.path}"
        }
    }

    private companion object {
        const val EXPRESSIVE_SIZE_TRANSITION_MIN_DURATION_MILLIS = 400
    }
}
