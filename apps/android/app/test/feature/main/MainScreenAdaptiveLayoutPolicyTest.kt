/*
 * Behavior Contract:
 * - Capability: the main screen places its sidebar and feed so that every window size keeps the feed and its
 *   top-bar actions on screen; owning layer: app; priority: P1.
 *
 * Scenarios:
 * - Given a compact or medium window, or a phone held sideways, when the sidebar placement resolves, then the
 *   sidebar is a modal drawer over the feed.
 * - Given an expanded or larger window, when the sidebar placement resolves, then the sidebar is a docked pane
 *   that takes width from the feed instead of pushing the feed off-screen.
 * - Given a phone, a foldable and a tablet window, when the modal drawer width resolves, then it leaves a
 *   tappable scrim strip and never exceeds the Material drawer maximum.
 * - Given the docked pane shown, hidden or anywhere in between, when the docked geometry resolves, then the
 *   pane's trailing edge meets the feed's leading edge and the feed always ends at the window edge — the old
 *   DismissibleNavigationDrawer translated a full-width feed, pushing the search and filter actions off-screen.
 * - Given each placement and pane state, when the navigation toggle resolves, then the top bar's navigation button
 *   names what a press does: open the modal drawer, collapse the shown pane or expand the hidden pane.
 *
 * Observable outcomes:
 * - The resolved sidebar placement, modal drawer width, and pane/feed offsets and widths in pixels.
 * - The navigation toggle kind that selects the button's accessible label.
 *
 * TDD proof:
 * - RED `./kotlin test --include-module=app --include-classes='com.lomo.app.feature.main.MainScreenAdaptiveLayoutPolicyTest'`
 *   with stubs reproducing the old layout (DismissibleNavigationDrawer translating a full-width feed; an 85%
 *   modal drawer) failed the docked-pane and drawer-width scenarios; GREEN after the pane/feed policy.
 * - RED for the navigation toggle: a stub that always resolved the modal drawer's open-menu toggle (the old fixed
 *   "Menu" label on a button that collapses the docked pane) failed the docked scenarios; GREEN after the toggle policy.
 *
 * Test Change Justification:
 * - Reason category: requested design change — the docked layout returns to one shared surface (design option A).
 * - Old behavior/assertion being replaced: the feed-frame scenario that inset the feed as a rounded pane on a
 *   surfaceContainer canvas (resolveMainDockedFeedFrame); that policy no longer exists.
 * - Why old assertion is no longer correct: the sidebar and the feed now share the window surface, so the feed fills
 *   the slot the docked geometry gives it; there is no inner frame to assert.
 * - Coverage preserved by: the docked-geometry scenario still asserts that the pane's trailing edge meets the feed's
 *   leading edge and that the feed ends at the window edge for every pane visibility.
 * - Why this is not fitting the test to the implementation: the user chose the design; the remaining assertions are
 *   window geometry that is observable on screen.
 *
 * Excludes:
 * - Compose rendering, drawer gestures, animation timing and window insets.
 */

package com.lomo.app.feature.main

import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

class MainScreenAdaptiveLayoutPolicyTest : AppFunSpec() {
    init {
        test("given compact and medium windows when the sidebar placement resolves then it is a modal drawer") {
            for (width in listOf(360, 411, 600, 700, 839)) {
                withClue("width=${width}dp") {
                    resolveMainSidebarPlacement(WindowSizeClass(minWidthDp = width, minHeightDp = 800)) shouldBe
                        MainSidebarPlacement.ModalDrawer
                }
            }
            withClue("a phone held sideways is wide but too short for a docked pane") {
                resolveMainSidebarPlacement(WindowSizeClass(minWidthDp = 891, minHeightDp = 411)) shouldBe
                    MainSidebarPlacement.ModalDrawer
            }
        }

        test("given expanded and larger windows when the sidebar placement resolves then it is a docked pane") {
            for (width in listOf(840, 1_280, 1_600)) {
                withClue("width=${width}dp") {
                    resolveMainSidebarPlacement(WindowSizeClass(minWidthDp = width, minHeightDp = 800)) shouldBe
                        MainSidebarPlacement.DockedPane
                }
            }
        }

        test(
            "given phone foldable and tablet windows when the modal drawer width resolves then a scrim strip remains",
        ) {
            assertSoftly {
                resolveMainModalDrawerWidth(windowWidth = 360.dp) shouldBe 304.dp
                resolveMainModalDrawerWidth(windowWidth = 411.dp) shouldBe 355.dp
                resolveMainModalDrawerWidth(windowWidth = 700.dp) shouldBe 360.dp
                resolveMainModalDrawerWidth(windowWidth = 40.dp) shouldBe 0.dp
            }
        }

        test(
            "given the docked pane at any visibility when the geometry resolves then the feed stays inside the window",
        ) {
            val window = 2_560
            val pane = 600
            for (step in 0..10) {
                val visibility = step / 10f
                val geometry = resolveMainDockedGeometry(
                    windowWidthPx = window,
                    paneWidthPx = pane,
                    paneVisibility = visibility,
                )
                withClue("visibility=$visibility geometry=$geometry") {
                    assertSoftly {
                        (geometry.paneOffsetPx + pane) shouldBe geometry.feedOffsetPx
                        (geometry.feedOffsetPx + geometry.feedWidthPx) shouldBe window
                    }
                }
            }
            resolveMainDockedGeometry(window, pane, paneVisibility = 1f) shouldBe
                MainDockedGeometry(paneOffsetPx = 0, feedOffsetPx = pane, feedWidthPx = window - pane)
            resolveMainDockedGeometry(window, pane, paneVisibility = 0f) shouldBe
                MainDockedGeometry(paneOffsetPx = -pane, feedOffsetPx = 0, feedWidthPx = window)
            resolveMainDockedGeometry(window, pane, paneVisibility = 1.004f) shouldBe
                MainDockedGeometry(paneOffsetPx = 0, feedOffsetPx = pane, feedWidthPx = window - pane)
        }

        test(
            "given each placement and pane state when the navigation toggle resolves then it names what a press does",
        ) {
            assertSoftly {
                for (paneShown in listOf(true, false)) {
                    withClue("modal drawer, remembered pane shown=$paneShown") {
                        resolveMainSidebarToggle(MainSidebarPlacement.ModalDrawer, dockedPaneShown = paneShown) shouldBe
                            MainSidebarToggle.OpenDrawer
                    }
                }
                resolveMainSidebarToggle(MainSidebarPlacement.DockedPane, dockedPaneShown = true) shouldBe
                    MainSidebarToggle.CollapsePane
                resolveMainSidebarToggle(MainSidebarPlacement.DockedPane, dockedPaneShown = false) shouldBe
                    MainSidebarToggle.ExpandPane
            }
        }
    }
}
