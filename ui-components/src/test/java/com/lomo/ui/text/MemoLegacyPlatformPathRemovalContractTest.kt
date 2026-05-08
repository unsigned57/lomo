package com.lomo.ui.text

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: memo text and input platform-path cleanup contract in ui-components.
 * - Behavior focus: memo display, selection/copy, and editor input must stay on the Compose-native
 *   implementations after the migration and must not keep the old TextView/EditText bridge files.
 * - Observable outcomes: deleted legacy source files and absence of legacy renderer flags.
 * - Red phase: Fails before the cleanup because the old TextView paragraph appearance,
 *   spannable/link bridge, platform selection-handle styling, and EditText input bridge files
 *   still exist in the source tree.
 * - Excludes: rendered pixels, IME OEM behavior, Android widget internals, and share-card canvas
 *   text rendering.
 */
class MemoLegacyPlatformPathRemovalContractTest {
    @Test
    fun `memo display and input no longer keep legacy platform bridge files`() {
        LEGACY_PLATFORM_SOURCE_PATHS.forEach { relativePath ->
            assertFalse(
                "$relativePath should be removed after the Compose-native memo text migration.",
                File(relativePath).exists(),
            )
        }
    }

    @Test
    fun `memo paragraph facade no longer exposes legacy renderer flags`() {
        val source = File("src/main/java/com/lomo/ui/text/MemoParagraphText.kt").readText()

        assertFalse(source.contains("MEMO_PARAGRAPH_USES_LEGACY_PLATFORM_RENDERING"))
        assertFalse(source.contains("MEMO_PARAGRAPH_USES_TEXT_VIEW_RENDERING"))
    }

    private companion object {
        private val LEGACY_PLATFORM_SOURCE_PATHS =
            listOf(
                "src/main/java/com/lomo/ui/text/MemoParagraphAppearance.kt",
                "src/main/java/com/lomo/ui/text/MemoParagraphSpannable.kt",
                "src/main/java/com/lomo/ui/text/PlatformTextSelectionHandleStyling.kt",
                "src/main/java/com/lomo/ui/text/MemoPlatformLetterSpacing.kt",
                "src/main/java/com/lomo/ui/component/input/MemoInputEditTextBridge.kt",
                "src/main/java/com/lomo/ui/component/input/MemoInputCursorStyling.kt",
                "src/main/java/com/lomo/ui/component/input/MemoInputParagraphPolicy.kt",
            )
    }
}
