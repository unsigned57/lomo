package com.lomo.ui.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: MemoComposeParagraphText selection and edit interaction policy.
 * - Behavior focus: free-copy memo text is Canvas-rendered, so it cannot directly host the
 *   BasicTextField selection implementation, but its handles must follow the Compose text-field
 *   anchor shape policy and body taps must delegate memo-card press feedback through the same
 *   shared interaction source without drawing a second body-level Material indication over the
 *   card-level memo mask.
 * - Observable outcomes: handle touch size and anchor offset policy, plus source-level absence
 *   of a body Material indication and press interaction for body double-tap edit.
 * - Red phase: Fails before the fix because body double-tap still installs a Material indication
 *   and emits a PressInteraction, stacking the body effect over the whole-memo quick-edit mask.
 * - Excludes: exact ripple pixels, Android floating-toolbar implementation, clipboard service,
 *   and BasicTextField's internal non-public selection-handle implementation.
 */
/*
 * Test Change Justification:
 * - Reason category: product contract correction.
 * - Old behavior/assertion being replaced: body double-tap edit was required to emit its own
 *   Material press feedback from MemoComposeParagraphText.
 * - Why old assertion is no longer correct: quick edit already has a memo-card-level visual
 *   mask, and the extra body indication creates the reported stacked animation.
 * - Coverage preserved by: the selection-handle assertions remain unchanged, and the new
 *   double-tap assertion still requires the body text path to delegate the edit callback.
 * - Why this is not fitting the test to the implementation: the new assertions encode the
 *   user-visible single-owner quick-edit effect instead of mirroring a code shape.
 */
class MemoComposeParagraphTextInteractionContractTest {
    @Test
    fun `free copy selection handle anchors below text like compose text field handles`() {
        val anchorPosition = Offset(x = 40f, y = 24f)

        assertEquals(25.dp, MemoTextSelectionHandleTouchSize)
        assertEquals(
            IntOffset(x = 28, y = 24),
            resolveMemoTextSelectionHandleTopLeft(
                anchorPosition = anchorPosition,
                handleSizePx = 25f,
            ),
        )
    }

    @Test
    fun `body double tap edit delegates without body material press feedback`() {
        val source = uiTextSourceFile("MemoComposeParagraphText.kt").readText()

        assertTrue(
            "Memo body double-tap should still delegate to the supplied edit callback.",
            source.contains("onDoubleTap = onDoubleClick?.let"),
        )
        assertFalse(
            "Memo body double-tap should not install a separate body-level Material indication.",
            source.contains("LocalIndication.current"),
        )
        assertFalse(
            "Memo body double-tap should not draw a separate body-level indication.",
            source.contains(".indication("),
        )
        assertFalse(
            "Memo body double-tap should not emit a separate press interaction over the card mask.",
            source.contains("PressInteraction.Press"),
        )
        assertFalse(
            "Memo body double-tap should not keep a dedicated body press-feedback helper.",
            source.contains("emitMemoTextEditPressFeedback"),
        )
    }

    @Test
    fun `body tap feedback is owned by memo card`() {
        val source = uiComponentsSourceFile("component/card/MemoCard.kt").readText()

        assertTrue(
            "Memo card should expose one shared interaction source for the whole-card mask.",
            source.contains("val cardInteractionSource = remember { MutableInteractionSource() }"),
        )
        assertTrue(
            "Memo card combinedClickable should use the shared interaction source.",
            source.contains("interactionSource = cardInteractionSource"),
        )
        assertTrue(
            "Memo card should keep a shared tap-feedback helper for body text.",
            source.contains("val memoCardTapFeedback ="),
        )
        assertTrue(
            "Memo card body text should use the shared memo-card tap feedback.",
            source.contains("onTapFeedback = memoCardTapFeedback"),
        )
        assertTrue(
            "Memo card should emit a single shared press mask for body taps.",
            source.contains("emitMemoCardPressFeedback("),
        )
    }

    @Test
    fun `body double tap edit still delegates edit callback only once`() {
        val source = uiTextSourceFile("MemoComposeParagraphText.kt").readText()

        assertTrue(
            "Memo body should still delegate double tap to the edit callback.",
            source.contains("onDoubleClick = onDoubleClick?.let"),
        )
        assertTrue(
            "Memo body should invoke shared tap feedback from the press path.",
            source.contains("onTapFeedback?.invoke()"),
        )
        assertTrue(
            "Memo body should expose a shared tap-feedback callback.",
            source.contains("onTapFeedback: (() -> Unit)? = null"),
        )
    }

    private fun uiTextSourceFile(fileName: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidates =
            listOf(
                currentDir.resolve("src/main/java/com/lomo/ui/text/$fileName"),
                currentDir.resolve("ui-components/src/main/java/com/lomo/ui/text/$fileName"),
            )
        return checkNotNull(candidates.firstOrNull(File::exists)) {
            "Failed to resolve ui-components text source file $fileName from ${currentDir.path}"
        }
    }

    private fun uiComponentsSourceFile(relativePath: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidates =
            listOf(
                currentDir.resolve("src/main/java/com/lomo/ui/$relativePath"),
                currentDir.resolve("ui-components/src/main/java/com/lomo/ui/$relativePath"),
            )
        return checkNotNull(candidates.firstOrNull(File::exists)) {
            "Failed to resolve ui-components source file $relativePath from ${currentDir.path}"
        }
    }
}
