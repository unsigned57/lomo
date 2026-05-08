package com.lomo.ui.component.common

import com.lomo.ui.component.card.MemoCardBodyTransitionMode
import com.lomo.ui.component.card.resolveMemoCardBodyMotionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: LazyListMotion item policy in ui-components.
 * - Behavior focus: lazy-list item entrance is a one-shot session, while resize/remove/insert
 *   sessions must own row movement and disable LazyColumn placement spring for affected rows.
 * - Observable outcomes: resolved lazy fade-in and placement-spring policy booleans; resize
 *   sessions block lazy placement spring for visible sibling rows through the post-settle sync
 *   frame; same-id content edits enter the resize session before the new height is known; resize
 *   settle timing outlasts memo body size motion; rows suppressed during an active entrance session
 *   do not replay that entrance.
 * - Red phase: Fails before the shared motion framework fix because app screens resolve
 *   animateItem behavior locally; the resize timing extension fails before this fix because the
 *   resize settle window is shorter than memo body size motion, so LazyColumn placement spring can
 *   be re-enabled before a collapse has visually settled; the content-edit test fails before this
 *   fix because only explicit expand/collapse actions can begin a resize session.
 * - Excludes: Compose frame timing, actual LazyColumn measurement, and pixel-level rendering.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression boundary correction.
 * - Old behavior/assertion being replaced: resize post-settle placement blockers were released
 *   by the first item-order sync after the size-animation timer elapsed.
 * - Why the old assertion is no longer correct: item-order sync can run before LazyList reports a
 *   stable post-resize viewport, which lets the operated row or adjacent rows recover LazyColumn
 *   motion one frame at a time and creates the reported sequential flicker.
 * - Coverage preserved by: the resize test still requires release after the transition has
 *   settled, but now also requires the shared framework to observe a post-settle viewport sync
 *   before release.
 * - Why this is not fitting the test to the implementation: the new assertion describes the
 *   user-visible owner boundary: resize motion remains the only row-movement owner until list
 *   layout has produced a stable post-resize snapshot.
 */
class LazyListMotionPolicyTest {
    @Test
    fun `initial entrance is one shot and is disabled after the entrance session settles`() {
        val active =
            resolveLazyListItemMotionPolicy(
                entranceState = LazyListItemEntranceState.Active,
                placementMode = LazyListItemPlacementMode.Spring,
                structureMotionActive = false,
            )
        val settled =
            resolveLazyListItemMotionPolicy(
                entranceState = LazyListItemEntranceState.Settled,
                placementMode = LazyListItemPlacementMode.Spring,
                structureMotionActive = false,
            )

        assertTrue(active.usesLazyItemFadeIn)
        assertTrue(active.usesPlacementSpring)
        assertFalse(settled.usesLazyItemFadeIn)
        assertTrue(settled.usesPlacementSpring)
    }

    @Test
    fun `structure motion disables lazy fade and placement spring for affected rows`() {
        val policy =
            resolveLazyListItemMotionPolicy(
                entranceState = LazyListItemEntranceState.Active,
                placementMode = LazyListItemPlacementMode.Spring,
                structureMotionActive = true,
            )

        assertFalse(policy.usesLazyItemFadeIn)
        assertFalse(policy.usesPlacementSpring)
    }

    @Test
    fun `disabled placement mode never enables lazy placement spring`() {
        val policy =
            resolveLazyListItemMotionPolicy(
                entranceState = LazyListItemEntranceState.Settled,
                placementMode = LazyListItemPlacementMode.Disabled,
                structureMotionActive = false,
            )

        assertFalse(policy.usesLazyItemFadeIn)
        assertFalse(policy.usesPlacementSpring)
    }

    @Test
    fun `resize settle window outlasts memo body size transform`() {
        val bodyMotionSpec = resolveMemoCardBodyMotionSpec(MemoCardBodyTransitionMode.StateContentTransform)

        assertTrue(
            "LazyList resize guard must stay active until memo body size animation has settled.",
            LAZY_LIST_RESIZE_TRANSITION_SETTLE_MILLIS >=
                bodyMotionSpec.sizeExitDurationMillis + RESIZE_POST_FRAME_BUFFER_MILLIS,
        )
    }

    @Test
    fun `resize session blocks placement spring for visible sibling rows through post settle sync`() {
        val state = LazyListResizeMotionState()
        val itemOrder =
            mapOf(
                "above" to 0,
                "expanded" to 1,
                "below" to 2,
            )
        state.updateItemOrder(itemOrder)

        val generation =
            state.beginTransition(
                itemId = "expanded",
                expands = false,
                snapshot =
                    LazyListMotionViewportSnapshot(
                        visibleItems =
                            listOf(
                                LazyListMotionVisibleItem(
                                    id = "above",
                                    index = 0,
                                    offsetPx = 0,
                                    sizePx = 80,
                                ),
                                LazyListMotionVisibleItem(
                                    id = "expanded",
                                    index = 1,
                                    offsetPx = 80,
                                    sizePx = 260,
                                ),
                                LazyListMotionVisibleItem(
                                    id = "below",
                                    index = 2,
                                    offsetPx = 340,
                                    sizePx = 120,
                                ),
                            ),
                        viewportStartPx = 0,
                        viewportEndPx = 700,
                    ),
            )

        assertTrue(state.blocksPlacementSpringFor("expanded"))
        assertTrue(state.blocksPlacementSpringFor("below"))
        assertFalse(state.blocksPlacementSpringFor("above"))

        state.settleTransition(generation)

        assertTrue(state.blocksPlacementSpringFor("expanded"))
        assertTrue(state.blocksPlacementSpringFor("below"))

        state.updateItemOrder(itemOrder)

        assertTrue(state.blocksPlacementSpringFor("expanded"))
        assertTrue(state.blocksPlacementSpringFor("below"))

        state.onVisibleItemsChanged(
            LazyListMotionViewportSnapshot(
                visibleItems =
                    listOf(
                        LazyListMotionVisibleItem(
                            id = "above",
                            index = 0,
                            offsetPx = 0,
                            sizePx = 80,
                        ),
                        LazyListMotionVisibleItem(
                            id = "expanded",
                            index = 1,
                            offsetPx = 80,
                            sizePx = 140,
                        ),
                        LazyListMotionVisibleItem(
                            id = "below",
                            index = 2,
                            offsetPx = 220,
                            sizePx = 120,
                        ),
                    ),
                viewportStartPx = 0,
                viewportEndPx = 700,
            ),
        )

        state.updateItemOrder(itemOrder)

        assertFalse(state.blocksPlacementSpringFor("expanded"))
        assertFalse(state.blocksPlacementSpringFor("below"))
    }

    @Test
    fun `same id content edit growth blocks edited row and lower siblings until post settle sync`() {
        val state = LazyListResizeMotionState()
        val itemOrder =
            mapOf(
                "above" to 0,
                "edited" to 1,
                "below" to 2,
            )
        state.updateItemOrder(itemOrder)

        val generation =
            state.beginContentResizeTransition(
                itemId = "edited",
                snapshot =
                    LazyListMotionViewportSnapshot(
                        visibleItems =
                            listOf(
                                LazyListMotionVisibleItem(
                                    id = "above",
                                    index = 0,
                                    offsetPx = 0,
                                    sizePx = 80,
                                ),
                                LazyListMotionVisibleItem(
                                    id = "edited",
                                    index = 1,
                                    offsetPx = 80,
                                    sizePx = 120,
                                ),
                                LazyListMotionVisibleItem(
                                    id = "below",
                                    index = 2,
                                    offsetPx = 200,
                                    sizePx = 100,
                                ),
                            ),
                        viewportStartPx = 0,
                        viewportEndPx = 700,
                    ),
            )

        assertTrue(state.blocksPlacementSpringFor("edited"))
        assertTrue(state.blocksPlacementSpringFor("below"))
        assertFalse(state.blocksPlacementSpringFor("above"))

        state.onItemMeasured(itemId = "edited", heightPx = 260)
        state.settleTransition(generation)
        state.updateItemOrder(itemOrder)

        assertTrue(state.blocksPlacementSpringFor("edited"))
        assertTrue(state.blocksPlacementSpringFor("below"))

        state.onVisibleItemsChanged(
            LazyListMotionViewportSnapshot(
                visibleItems =
                    listOf(
                        LazyListMotionVisibleItem(
                            id = "above",
                            index = 0,
                            offsetPx = 0,
                            sizePx = 80,
                        ),
                        LazyListMotionVisibleItem(
                            id = "edited",
                            index = 1,
                            offsetPx = 80,
                            sizePx = 260,
                        ),
                        LazyListMotionVisibleItem(
                            id = "below",
                            index = 2,
                            offsetPx = 340,
                            sizePx = 100,
                        ),
                    ),
                viewportStartPx = 0,
                viewportEndPx = 700,
            ),
        )
        state.updateItemOrder(itemOrder)

        assertFalse(state.blocksPlacementSpringFor("edited"))
        assertFalse(state.blocksPlacementSpringFor("below"))
    }

    @Test
    fun `completed resize viewport entries remain blocked through post settle viewport sync`() {
        val state = LazyListResizeMotionState()
        val itemOrder =
            mapOf(
                "above" to 0,
                "expanded" to 1,
                "below" to 2,
            )
        state.updateItemOrder(itemOrder)

        val generation =
            state.beginTransition(
                itemId = "expanded",
                expands = false,
                snapshot =
                    LazyListMotionViewportSnapshot(
                        visibleItems =
                            listOf(
                                LazyListMotionVisibleItem(
                                    id = "expanded",
                                    index = 1,
                                    offsetPx = 0,
                                    sizePx = 520,
                                ),
                                LazyListMotionVisibleItem(
                                    id = "below",
                                    index = 2,
                                    offsetPx = 520,
                                    sizePx = 120,
                                ),
                            ),
                        viewportStartPx = 0,
                        viewportEndPx = 700,
                    ),
            )

        state.onVisibleItemsChanged(
            LazyListMotionViewportSnapshot(
                visibleItems =
                    listOf(
                        LazyListMotionVisibleItem(
                            id = "above",
                            index = 0,
                            offsetPx = -80,
                            sizePx = 160,
                        ),
                        LazyListMotionVisibleItem(
                            id = "expanded",
                            index = 1,
                            offsetPx = 80,
                            sizePx = 520,
                        ),
                        LazyListMotionVisibleItem(
                            id = "below",
                            index = 2,
                            offsetPx = 600,
                            sizePx = 120,
                        ),
                    ),
                viewportStartPx = 0,
                viewportEndPx = 700,
            ),
        )

        val guard = requireNotNull(state.viewportEntryGuardFor("above"))
        assertEquals(0f, guard.initialOffsetPx, 0.0f)
        assertTrue(state.blocksPlacementSpringFor("above"))

        state.clearViewportEntryGuard("above")
        assertTrue(state.blocksPlacementSpringFor("above"))

        state.settleTransition(generation)
        assertTrue(state.blocksPlacementSpringFor("above"))

        state.onVisibleItemsChanged(
            LazyListMotionViewportSnapshot(
                visibleItems =
                    listOf(
                        LazyListMotionVisibleItem(
                            id = "above",
                            index = 0,
                            offsetPx = -20,
                            sizePx = 160,
                        ),
                        LazyListMotionVisibleItem(
                            id = "expanded",
                            index = 1,
                            offsetPx = 140,
                            sizePx = 240,
                        ),
                        LazyListMotionVisibleItem(
                            id = "below",
                            index = 2,
                            offsetPx = 380,
                            sizePx = 120,
                        ),
                    ),
                viewportStartPx = 0,
                viewportEndPx = 700,
            ),
        )
        state.updateItemOrder(itemOrder)

        assertFalse(state.blocksPlacementSpringFor("above"))
    }

    @Test
    fun `structure motion during active entrance prevents later entrance replay for the same item`() {
        val state = LazyListMotionEntranceState()

        state.recordResolvedItem(
            itemId = "below",
            entranceState = LazyListItemEntranceState.Active,
            structureMotionActive = true,
        )

        assertTrue(
            state.blocksEntranceRecovery(
                itemId = "below",
                entranceState = LazyListItemEntranceState.Active,
            ),
        )

        state.recordResolvedItem(
            itemId = "below",
            entranceState = LazyListItemEntranceState.Settled,
            structureMotionActive = false,
        )

        assertFalse(
            state.blocksEntranceRecovery(
                itemId = "below",
                entranceState = LazyListItemEntranceState.Active,
            ),
        )
    }

    private companion object {
        const val RESIZE_POST_FRAME_BUFFER_MILLIS = 80L
    }
}
