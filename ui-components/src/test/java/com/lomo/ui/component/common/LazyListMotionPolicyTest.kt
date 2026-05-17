package com.lomo.ui.component.common

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import com.lomo.ui.component.card.MemoCardBodyTransitionMode
import com.lomo.ui.component.card.resolveMemoCardBodyMotionSpec

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
class LazyListMotionPolicyTest : UiComponentsFunSpec() {
    init {
        test("initial entrance is one shot and is disabled after the entrance session settles") {
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

        (active.usesLazyItemFadeIn) shouldBe true
        (active.usesPlacementSpring) shouldBe true
        (settled.usesLazyItemFadeIn) shouldBe false
        (settled.usesPlacementSpring) shouldBe true
        }
    }

    init {
        test("structure motion disables lazy fade and placement spring for affected rows") {
        val policy =
            resolveLazyListItemMotionPolicy(
                entranceState = LazyListItemEntranceState.Active,
                placementMode = LazyListItemPlacementMode.Spring,
                structureMotionActive = true,
            )

        (policy.usesLazyItemFadeIn) shouldBe false
        (policy.usesPlacementSpring) shouldBe false
        }
    }

    init {
        test("disabled placement mode never enables lazy placement spring") {
        val policy =
            resolveLazyListItemMotionPolicy(
                entranceState = LazyListItemEntranceState.Settled,
                placementMode = LazyListItemPlacementMode.Disabled,
                structureMotionActive = false,
            )

        (policy.usesLazyItemFadeIn) shouldBe false
        (policy.usesPlacementSpring) shouldBe false
        }
    }

    init {
        test("resize settle window outlasts memo body size transform") {
        val bodyMotionSpec = resolveMemoCardBodyMotionSpec(MemoCardBodyTransitionMode.StateContentTransform)

        withClue("LazyList resize guard must stay active until memo body size animation has settled.") { (LAZY_LIST_RESIZE_TRANSITION_SETTLE_MILLIS >=
                bodyMotionSpec.sizeExitDurationMillis + RESIZE_POST_FRAME_BUFFER_MILLIS) shouldBe true }
        }
    }

    init {
        test("resize session blocks placement spring for visible sibling rows through post settle sync") {
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

        (state.blocksPlacementSpringFor("expanded")) shouldBe true
        (state.blocksPlacementSpringFor("below")) shouldBe true
        (state.blocksPlacementSpringFor("above")) shouldBe false

        state.settleTransition(generation)

        (state.blocksPlacementSpringFor("expanded")) shouldBe true
        (state.blocksPlacementSpringFor("below")) shouldBe true

        state.updateItemOrder(itemOrder)

        (state.blocksPlacementSpringFor("expanded")) shouldBe true
        (state.blocksPlacementSpringFor("below")) shouldBe true

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

        (state.blocksPlacementSpringFor("expanded")) shouldBe false
        (state.blocksPlacementSpringFor("below")) shouldBe false
        }
    }

    init {
        test("same id content edit growth blocks edited row and lower siblings until post settle sync") {
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

        (state.blocksPlacementSpringFor("edited")) shouldBe true
        (state.blocksPlacementSpringFor("below")) shouldBe true
        (state.blocksPlacementSpringFor("above")) shouldBe false

        state.onItemMeasured(itemId = "edited", heightPx = 260)
        state.settleTransition(generation)
        state.updateItemOrder(itemOrder)

        (state.blocksPlacementSpringFor("edited")) shouldBe true
        (state.blocksPlacementSpringFor("below")) shouldBe true

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

        (state.blocksPlacementSpringFor("edited")) shouldBe false
        (state.blocksPlacementSpringFor("below")) shouldBe false
        }
    }

    init {
        test("completed resize viewport entries remain blocked through post settle viewport sync") {
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
        (guard.initialOffsetPx) shouldBe ((0f) plusOrMinus (0.0f))
        (state.blocksPlacementSpringFor("above")) shouldBe true

        state.clearViewportEntryGuard("above")
        (state.blocksPlacementSpringFor("above")) shouldBe true

        state.settleTransition(generation)
        (state.blocksPlacementSpringFor("above")) shouldBe true

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

        (state.blocksPlacementSpringFor("above")) shouldBe false
        }
    }

    init {
        test("structure motion during active entrance prevents later entrance replay for the same item") {
        val state = LazyListMotionEntranceState()

        state.recordResolvedItem(
            itemId = "below",
            entranceState = LazyListItemEntranceState.Active,
            structureMotionActive = true,
        )

        (state.blocksEntranceRecovery(
                itemId = "below",
                entranceState = LazyListItemEntranceState.Active,
            )) shouldBe true

        state.recordResolvedItem(
            itemId = "below",
            entranceState = LazyListItemEntranceState.Settled,
            structureMotionActive = false,
        )

        (state.blocksEntranceRecovery(
                itemId = "below",
                entranceState = LazyListItemEntranceState.Active,
            )) shouldBe false
        }
    }

    private companion object {
        const val RESIZE_POST_FRAME_BUFFER_MILLIS = 80L
    }
}
