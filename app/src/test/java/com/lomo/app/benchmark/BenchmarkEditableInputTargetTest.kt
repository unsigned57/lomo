package com.lomo.app.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: Benchmark editable-input target resolution policy.
 * - Behavior focus: baseline input flows must resolve a tagged container to the actual editable
 *   descendant when the anchor itself is not an EditText.
 * - Observable outcomes: resolved node path for direct-editable, nested-editable, and missing-editable
 *   trees.
 * - Red phase: Fails before the fix because the policy only accepts anchors that are already
 *   EditText nodes, so tagged containers with nested editable descendants never become writable.
 * - Excludes: UiAutomator device timing, Compose semantics wiring, Android EditText widget behavior,
 *   and IME rendering.
 */
class BenchmarkEditableInputTargetTest {
    @Test
    fun `editable anchor resolves to itself`() {
        val path =
            BenchmarkUiNodeSnapshot(
                className = ANDROID_EDIT_TEXT_CLASS_NAME,
            ).editableInputPathOrSelf()

        assertEquals(emptyList<Int>(), path)
    }

    @Test
    fun `container anchor resolves to nested editable descendant`() {
        val path =
            BenchmarkUiNodeSnapshot(
                className = "android.view.ViewGroup",
                children =
                    listOf(
                        BenchmarkUiNodeSnapshot(
                            className = "android.view.ViewGroup",
                            children =
                                listOf(
                                    BenchmarkUiNodeSnapshot(className = "android.widget.TextView"),
                                    BenchmarkUiNodeSnapshot(className = ANDROID_EDIT_TEXT_CLASS_NAME),
                                ),
                        ),
                    ),
            ).editableInputPathOrSelf()

        assertEquals(listOf(0, 1), path)
    }

    @Test
    fun `anchor without editable descendant returns null`() {
        val path =
            BenchmarkUiNodeSnapshot(
                className = "android.view.ViewGroup",
                children =
                    listOf(
                        BenchmarkUiNodeSnapshot(className = "android.view.ViewGroup"),
                        BenchmarkUiNodeSnapshot(className = "android.widget.TextView"),
                    ),
            ).editableInputPathOrSelf()

        assertNull(path)
    }
}
