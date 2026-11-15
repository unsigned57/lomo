package com.lomo.ui.component.markdown

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * Scenarios:
 * - Given a non-navigable image preview, when its transition key is resolved, then no shared key is exposed.
 * - Given navigable images with duplicate destinations, when a namespace is supplied, then their transition
 *   keys remain deterministic and distinct.
 * - Unit under test: markdown image shared-transition key policy
 * - Capability: non-navigable markdown image previews must not register shared-element keys, while navigable images keep deterministic keys and optional namespace isolation.
 * - Observable outcomes: nullable shared-element key returned for a destination under different navigation and namespace conditions.
 * - TDD proof: Fails before the fix because markdown images always inherit destination as a shared-element key, even in non-clickable history previews that can render the same image multiple times and crash shared transitions.
 * - Excludes: Compose rendering, Coil painter state, image decoding, and navigation animation execution.
 *
 * Test Change Justification:
 * Reason category: Mechanical formatting and import alignment for MarkdownImageSharedTransitionKeyPolicy.
 * Old behavior/assertion being replaced: None; existing scenario bodies and expected results are retained.
 * Why old assertion is no longer correct: Existing assertions remain correct. Layout, imports, and equivalent
 * fixture declaration syntax do not change their behavior.
 * Coverage preserved by: The existing scenarios, including non navigable markdown image preview does not
 * expose a shared transition key; navigable markdown image preview keeps destination as shared transition key
 * by default.
 * Why this is not fitting the test to the implementation: No expected result is relaxed or replaced; the
 * original behavior locks remain executable.
 */
class MarkdownImageSharedTransitionKeyPolicyTest : UiComponentsFunSpec() {
    init {
        test("non navigable markdown image preview does not expose a shared transition key") {
            (
                resolveMarkdownImageSharedElementKey(
                    destination = "content://images/history.png",
                    hasNavigationTarget = false,
                )
                ) shouldBe null
        }
    }

    init {
        test("navigable markdown image preview keeps destination as shared transition key by default") {
            (
                resolveMarkdownImageSharedElementKey(
                    destination = "content://images/history.png",
                    hasNavigationTarget = true,
                )
                ) shouldBe ("content://images/history.png")
        }
    }

    init {
        test("namespace isolates shared transition keys for duplicate image destinations") {
            (
                resolveMarkdownImageSharedElementKey(
                    destination = "content://images/history.png",
                    hasNavigationTarget = true,
                    namespace = "history:r2",
                )
                ) shouldBe ("history:r2|content://images/history.png")
        }
    }
}
