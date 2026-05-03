package com.lomo.app.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: DeleteViewportEntryCompensationState.
 * - Behavior focus: newly visible rows below or above the deleting row should receive one-shot
 *   directional compensation during the collapse window, already-visible rows above the deleting
 *   row should not be compensated, oversized collapse distances should clamp to the viewport
 *   height, and top-entry compensation should stay on the shared remaining-distance timeline
 *   instead of replaying each row's local overshoot. Once a top-edge entry batch is detected,
 *   already-visible rows above the deleting row must join that same timeline so new rows do not
 *   catch up into them, while later rows entering from above during a bottom-anchored delete must
 *   stay hidden until they actually reach the viewport instead of borrowing the shared offset
 *   early and flashing in. Rows entering from below after a top delete must also join the same
 *   absolute remaining-distance timeline once a below-entry batch has started, so later entrants
 *   cannot overtake the first visible entrant.
 * - Observable outcomes: pending compensation payloads plus shared/hold offsets exposed via
 *   compensationFor(itemId), sharedTopEntryOffsetFor(itemId), and holdOffsetFor(itemId).
 * - Red phase: Fails before the fix because no composable-side delete session tracks collapse
 *   distance or newly visible Paging rows; the snapshot overload is the sole intake. It also
 *   fails before the top-entry fix because rows above the deleting row use their local overshoot
 *   instead of the shared remaining distance, so multi-row top entries drift apart.
 * - Excludes: Compose rendering frames, LazyListState internals, and ViewModel wiring.
 */
/*
 * Test Change Justification:
 * - Reason category: product bug regression boundary extension.
 * - Old behavior/assertion being replaced: the previous bottom-delete assertion expected a later
 *   top-entry row to mirror the shared offset before that row actually became visible. Later
 *   assertions also expected already-visible rows above a bottom-anchored delete to receive a
 *   shared compensation placement.
 * - Why the old assertion is no longer correct: on-device behavior still flashes because an
 *   off-screen row should stay hidden until it genuinely enters the viewport; sharing the live
 *   offset too early makes the row partially appear before its reveal frame. Moving initially
 *   visible rows with viewport-entry compensation also creates a visible downward drift and
 *   rebound after bottom deletes; only rows that actually enter from outside the viewport should
 *   receive entry compensation. A positive bottom top-entry one-shot offset is also incorrect
 *   because it makes the row become opaque in the middle of the viewport instead of entering
 *   smoothly from above.
 * - Coverage preserved by: top-delete shared-timeline assertions for already-visible rows remain
 *   intact, while bottom-delete assertions lock the hidden pre-entry phase and one-shot
 *   compensation for genuinely entering rows separately.
 * - Why this is not fitting the test to the implementation: the revised contract matches the
 *   user-visible no-flash requirement rather than the earlier internal offset strategy.
 */
class DeleteViewportEntryCompensationStateTest {
    private var currentTimeMillis = 1_000L

    @Test
    fun `bottom delete treats prelayout row outside viewport as delayed top entry`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "already-visible" to 1,
                "delete" to 2,
            ),
        )
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -260,
                                bottomPx = -80,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 0,
                                bottomPx = 160,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 160,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        state.syncSession(deletingMemoId = "delete")
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        assertEquals(
            "Expected an above-viewport prelayout row to stay on the delayed bottom top-entry path once it intersects the viewport.",
            0f,
            state.holdOffsetFor("entering") ?: Float.NaN,
            0.0f,
        )
        assertNull(state.compensationFor("entering"))

        currentTimeMillis = 1_520L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -96,
                                bottomPx = 64,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 64,
                                bottomPx = 224,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 224,
                                bottomPx = 360,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val compensation = requireNotNull(state.compensationFor("entering"))
        assertTrue(
            "Expected the first bottom top-entry row to receive one-shot compensation from above instead of falling back to a slower placement spring.",
            64 + compensation.initialOffsetPx <= 0f,
        )
    }

    @Test
    fun `row entering below deleting row receives remaining collapse compensation`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "entering" to 1,
                "tail" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 300,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_350L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = 40,
                                bottomPx = 200,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 1_000,
                ),
        )

        val compensation = state.compensationFor("entering")
        requireNotNull(compensation)
        assertEquals(710, compensation.durationMillis)
        assertTrue(
            "Expected remaining collapse distance to stay close to 291 px, but was ${compensation.initialOffsetPx}.",
            kotlin.math.abs(compensation.initialOffsetPx - 291f) <= 0.5f,
        )
    }

    @Test
    fun `row above deleting row is skipped and entering row is only compensated once`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "before" to 0,
                "delete" to 1,
                "entering" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("before", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 1,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "before",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 40,
                                bottomPx = 280,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = 280,
                                bottomPx = 440,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 1_000,
                ),
        )

        assertNull(state.compensationFor("before"))
        assertTrue(state.compensationFor("entering") != null)

        state.clearCompensation("entering")
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "before",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 40,
                                bottomPx = 280,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 1_000,
                ),
        )
        currentTimeMillis = 1_420L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "before",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 40,
                                bottomPx = 280,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = 280,
                                bottomPx = 440,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 1_000,
                ),
        )

        // Without consumedIds, re-entering rows receive a fresh time-appropriate placement.
        val reentry = state.compensationFor("entering")
        requireNotNull(reentry)
        assertEquals(640, reentry.durationMillis)
        assertTrue(
            "Expected re-entry offset close to 212 px, but was ${reentry.initialOffsetPx}.",
            kotlin.math.abs(reentry.initialOffsetPx - 212f) <= 0.5f,
        )
    }

    @Test
    fun `row entering above deleting row receives shared remaining-distance compensation`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "visible" to 1,
                "delete" to 2,
                "tail" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        val expectedRemainingDistancePx = 232.10526f
        assertTrue(
            "Expected the top-entry row to hold the shared remaining distance before compensation is attached.",
            kotlin.math.abs((state.holdOffsetFor("entering") ?: Float.NaN) + expectedRemainingDistancePx) <= 0.5f,
        )
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val compensation = state.compensationFor("entering")
        requireNotNull(compensation)
        assertEquals(700, compensation.durationMillis)
        assertTrue(
            "Expected the top-entry row to start from the shared remaining distance instead of local overshoot, but was ${compensation.initialOffsetPx}.",
            kotlin.math.abs(compensation.initialOffsetPx + expectedRemainingDistancePx) <= 0.5f,
        )
    }

    @Test
    fun `multiple rows entering above deleting row share the same top-entry timeline`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering-a" to 0,
                "entering-b" to 1,
                "visible" to 2,
                "delete" to 3,
                "tail" to 4,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 3,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        val expectedRemainingDistancePx = 232.10526f

        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-b",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 320,
                                bottomPx = 456,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val compensationA = requireNotNull(state.compensationFor("entering-a"))
        val compensationB = requireNotNull(state.compensationFor("entering-b"))

        assertEquals(700, compensationA.durationMillis)
        assertEquals(700, compensationB.durationMillis)
        assertTrue(
            "Expected the first top-entry row to use the shared remaining distance, but was ${compensationA.initialOffsetPx}.",
            kotlin.math.abs(compensationA.initialOffsetPx + expectedRemainingDistancePx) <= 0.5f,
        )
        assertTrue(
            "Expected the second top-entry row to use the shared remaining distance, but was ${compensationB.initialOffsetPx}.",
            kotlin.math.abs(compensationB.initialOffsetPx + expectedRemainingDistancePx) <= 0.5f,
        )
    }

    @Test
    fun `multiple rows entering below top deleting row share the same remaining-distance timeline`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "entering-a" to 1,
                "entering-b" to 2,
                "tail" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 300,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_350L
        val expectedRemainingDistancePx = 291.4737f
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = 40,
                                bottomPx = 200,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-b",
                                topPx = 200,
                                bottomPx = 360,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val firstEntry = requireNotNull(state.compensationFor("entering-a"))
        val secondEntry = requireNotNull(state.compensationFor("entering-b"))

        assertEquals(firstEntry.durationMillis, secondEntry.durationMillis)
        assertTrue(
            "Expected the first below-entry row to use the shared remaining distance, but was ${firstEntry.initialOffsetPx}.",
            kotlin.math.abs(firstEntry.initialOffsetPx - expectedRemainingDistancePx) <= 0.5f,
        )
        assertTrue(
            "Expected the second below-entry row to use the same shared remaining distance instead of its local viewport overshoot, but first=$firstEntry and second=$secondEntry.",
            kotlin.math.abs(secondEntry.initialOffsetPx - expectedRemainingDistancePx) <= 0.5f,
        )
    }

    @Test
    fun `later row entering below top deleting row starts on the established absolute timeline`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "entering-a" to 1,
                "entering-b" to 2,
                "tail" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 300,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_350L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = 40,
                                bottomPx = 200,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        val firstEntry = requireNotNull(state.compensationFor("entering-a"))

        currentTimeMillis = 1_420L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = 0,
                                bottomPx = 160,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-b",
                                topPx = 160,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val laterEntry = requireNotNull(state.compensationFor("entering-b"))
        val expectedFirstEntryOffsetAtLaterFrame =
            firstEntry.initialOffsetPx * (laterEntry.durationMillis.toFloat() / firstEntry.durationMillis.toFloat())

        assertEquals(640, laterEntry.durationMillis)
        assertTrue(
            "Expected the later below-entry row to start on the same absolute timeline as the first row, but firstEntry=$firstEntry, laterEntry=$laterEntry, expectedFirstEntryOffsetAtLaterFrame=$expectedFirstEntryOffsetAtLaterFrame.",
            kotlin.math.abs(laterEntry.initialOffsetPx - expectedFirstEntryOffsetAtLaterFrame) <= 0.5f,
        )
    }

    @Test
    fun `top-entry batch aligns with already visible row above deleting row`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "already-visible" to 1,
                "delete" to 2,
                "tail" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("already-visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val enteringCompensation = requireNotNull(state.compensationFor("entering"))
        val alreadyVisibleCompensation = requireNotNull(state.compensationFor("already-visible"))

        assertEquals(enteringCompensation.durationMillis, alreadyVisibleCompensation.durationMillis)
        assertTrue(
            "Expected the already-visible row above the deleting row to share the top-entry offset so the entering row cannot catch up, but entering=$enteringCompensation and alreadyVisible=$alreadyVisibleCompensation.",
            kotlin.math.abs(enteringCompensation.initialOffsetPx - alreadyVisibleCompensation.initialOffsetPx) <= 0.5f,
        )
    }

    @Test
    fun `bottom delete leaves already visible rows uncompensated while later top-entry rows stay hidden`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "already-visible" to 1,
                "delete" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("already-visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        assertNull(
            "Expected already-visible rows above a bottom delete to stay on normal placement only, because viewport-entry compensation makes them drift down and rebound.",
            state.sharedTopEntryCompensationFor("already-visible"),
        )
        assertNull(
            "Expected already-visible rows above a bottom delete not to be hidden or held by viewport-entry compensation.",
            state.holdOffsetFor("already-visible"),
        )
        assertEquals(
            0f,
            state.holdOffsetFor("entering") ?: Float.NaN,
            0.0f,
        )
        assertNull(
            "Expected the later-entering row above the bottom deleting row to stay hidden until it really reaches the viewport instead of borrowing the shared offset early.",
            state.sharedTopEntryOffsetFor("entering"),
        )
    }

    @Test
    fun `bottom top-entry row uses one-shot compensation once delayed reveal becomes eligible`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "already-visible" to 1,
                "delete" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("already-visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        assertEquals(0f, state.holdOffsetFor("entering") ?: Float.NaN, 0.0f)
        assertNull(state.compensationFor("entering"))

        currentTimeMillis = 1_520L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -96,
                                bottomPx = 64,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 64,
                                bottomPx = 224,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 224,
                                bottomPx = 360,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val enteringCompensation = requireNotNull(state.compensationFor("entering"))
        assertTrue(
            "Expected the delayed bottom top-entry row to receive a negative one-shot placement compensation from above instead of switching to the raw shared offset path, but was $enteringCompensation.",
            enteringCompensation.initialOffsetPx < 0f,
        )
        assertNull(
            "Expected newly revealed bottom top-entry rows to avoid the raw shared-offset path so their alpha and motion cannot flicker on recomposition.",
            state.sharedTopEntryOffsetFor("entering"),
        )
    }

    @Test
    fun `bottom top-entry one-shot compensation starts above viewport and survives delete cleanup`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "already-visible" to 1,
                "delete" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("already-visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        currentTimeMillis = 1_520L
        val enteringBottomPx = 64
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -96,
                                bottomPx = enteringBottomPx,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 64,
                                bottomPx = 224,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 224,
                                bottomPx = 360,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val compensation = requireNotNull(state.compensationFor("entering"))
        assertTrue(
            "Expected bottom top-entry placement to start above the viewport so alpha reveal cannot flash it into the middle of the screen, but was $compensation.",
            enteringBottomPx + compensation.initialOffsetPx <= 0f,
        )

        state.syncSession(
            deletingMemoId = null,
            currentVisibleIds = setOf("entering", "already-visible"),
        )

        assertEquals(
            "Expected the pending top-entry placement to survive delete-id cleanup until the modifier consumes the animation.",
            compensation,
            state.compensationFor("entering"),
        )
    }

    @Test
    fun `bottom top-entry row stays held on the reveal frame until compensation is attached`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "already-visible" to 1,
                "delete" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("already-visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "already-visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        currentTimeMillis = 1_520L

        assertEquals(
            "Expected the reveal-eligible row to keep blocking animateItem placement until onVisibleItemsChanged attaches its one-shot compensation.",
            0f,
            state.holdOffsetFor("entering") ?: Float.NaN,
            0.0f,
        )
        assertNull(state.compensationFor("entering"))
    }

    @Test
    fun `later top-entry row starts where the earlier top-entry row has progressed`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering-a" to 0,
                "entering-b" to 1,
                "visible" to 2,
                "delete" to 3,
                "tail" to 4,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 3,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        val firstEntry = requireNotNull(state.compensationFor("entering-a"))

        currentTimeMillis = 1_420L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = -96,
                                bottomPx = 64,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-b",
                                topPx = 64,
                                bottomPx = 224,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 224,
                                bottomPx = 360,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 360,
                                bottomPx = 496,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        val laterEntry = requireNotNull(state.compensationFor("entering-b"))
        val expectedFirstEntryOffsetAtLaterFrame =
            firstEntry.initialOffsetPx * (laterEntry.durationMillis.toFloat() / firstEntry.durationMillis.toFloat())

        assertEquals(640, laterEntry.durationMillis)
        assertTrue(
            "Expected the later top-entry row to start on the same absolute timeline as the earlier row, but firstEntry=$firstEntry, laterEntry=$laterEntry, expectedFirstEntryOffsetAtLaterFrame=$expectedFirstEntryOffsetAtLaterFrame.",
            kotlin.math.abs(laterEntry.initialOffsetPx - expectedFirstEntryOffsetAtLaterFrame) <= 0.5f,
        )
    }

    @Test
    fun `row entering above deleting row is held until top edge compensation is ready`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "visible" to 1,
                "delete" to 2,
                "tail" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L

        assertTrue(
            "Expected the top-entry row to stay hidden for the first realised frame until its compensation can be applied.",
            state.shouldHoldUntilCompensated("entering"),
        )

        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        assertFalse(
            "Expected the row to stop using the hidden first-frame hold once compensation is available.",
            state.shouldHoldUntilCompensated("entering"),
        )
    }

    @Test
    fun `top-entry row visible before collapse window still receives shared compensation`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering" to 0,
                "visible" to 1,
                "delete" to 2,
                "tail" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 2,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_300L
        val snapshotWithEarlyTopEntry =
            DeleteViewportEntryVisibilitySnapshot(
                visibleItems =
                    listOf(
                        DeleteViewportEntryVisibleItem(
                            id = "entering",
                            topPx = -136,
                            bottomPx = 24,
                        ),
                        DeleteViewportEntryVisibleItem(
                            id = "visible",
                            topPx = 24,
                            bottomPx = 184,
                        ),
                        DeleteViewportEntryVisibleItem(
                            id = "delete",
                            topPx = 184,
                            bottomPx = 320,
                        ),
                    ),
                viewportStartPx = 0,
                viewportEndPx = 320,
            )
        state.onVisibleItemsChanged(snapshotWithEarlyTopEntry)
        assertNull(state.compensationFor("entering"))

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(snapshotWithEarlyTopEntry)

        val compensation = state.compensationFor("entering")
        requireNotNull(compensation)
        assertTrue(
            "Expected an already-visible top-entry row to still receive the shared remaining-distance compensation once the collapse window opens, but was ${compensation.initialOffsetPx}.",
            kotlin.math.abs(compensation.initialOffsetPx + 232.10526f) <= 0.5f,
        )
    }

    @Test
    fun `oversized collapse distance clamps compensation to viewport height`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "entering" to 1,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 1_200,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_320L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = 0,
                                bottomPx = 1_200,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val compensation = state.compensationFor("entering")
        requireNotNull(compensation)
        assertEquals(740, compensation.durationMillis)
        assertEquals(320f, compensation.initialOffsetPx)
    }

    @Test
    fun `newly visible row only compensates the viewport overshoot it already exposed`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "visible" to 1,
                "entering" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete", "visible"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 300,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_350L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 40,
                                bottomPx = 280,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = 304,
                                bottomPx = 420,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val compensation = state.compensationFor("entering")
        requireNotNull(compensation)
        assertEquals(710, compensation.durationMillis)
        assertEquals(16f, compensation.initialOffsetPx)
    }

    @Test
    fun `ending delete session keeps pending compensation until the row consumes it`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "entering" to 1,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 300,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_350L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering",
                                topPx = 304,
                                bottomPx = 420,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        val pendingBeforeClear = state.compensationFor("entering")
        requireNotNull(pendingBeforeClear)

        state.syncSession(deletingMemoId = null)

        assertEquals(
            pendingBeforeClear,
            state.compensationFor("entering"),
        )
    }

    @Test
    fun `session keeps delayed bottom top-entry holds active after deleting id is cleared`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "entering-a" to 0,
                "entering-b" to 1,
                "visible" to 2,
                "delete" to 3,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("visible", "delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 3,
            isDeleting = true,
            heightPx = 240,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_360L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = -136,
                                bottomPx = 24,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 24,
                                bottomPx = 184,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 184,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        assertEquals(
            0f,
            state.holdOffsetFor("entering-a") ?: Float.NaN,
            0.0f,
        )
        assertNull(state.sharedTopEntryOffsetFor("entering-a"))

        // Simulate deleting-id cleanup happening before the full collapse+settle timeline has elapsed.
        state.syncSession(
            deletingMemoId = null,
            currentVisibleIds = setOf("entering-a", "visible"),
        )

        currentTimeMillis = 1_420L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = -96,
                                bottomPx = 64,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-b",
                                topPx = 64,
                                bottomPx = 224,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "visible",
                                topPx = 224,
                                bottomPx = 360,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        assertEquals(
            0f,
            state.holdOffsetFor("entering-b") ?: Float.NaN,
            0.0f,
        )
        assertNull(
            "Expected the later bottom top-entry row to stay on the delayed hold path while collapse is still in flight even after deleting id cleanup.",
            state.sharedTopEntryOffsetFor("entering-b"),
        )
    }

    @Test
    fun `top delete keeps compensating later entering rows after deleting id is cleared`() {
        val state = newState()

        state.updateItemOrder(
            mapOf(
                "delete" to 0,
                "entering-a" to 1,
                "entering-b" to 2,
            ),
        )
        state.syncSession(
            deletingMemoId = "delete",
            currentVisibleIds = setOf("delete"),
        )
        state.onItemMeasured(
            itemId = "delete",
            itemIndex = 0,
            isDeleting = true,
            heightPx = 300,
            bottomSpacingPx = 12,
        )

        currentTimeMillis = 1_350L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "delete",
                                topPx = 0,
                                bottomPx = 40,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = 40,
                                bottomPx = 200,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )
        requireNotNull(state.compensationFor("entering-a"))
        state.clearCompensation("entering-a")

        state.syncSession(
            deletingMemoId = null,
            currentVisibleIds = setOf("entering-a"),
        )

        currentTimeMillis = 1_420L
        state.onVisibleItemsChanged(
            snapshot =
                DeleteViewportEntryVisibilitySnapshot(
                    visibleItems =
                        listOf(
                            DeleteViewportEntryVisibleItem(
                                id = "entering-a",
                                topPx = 0,
                                bottomPx = 160,
                            ),
                            DeleteViewportEntryVisibleItem(
                                id = "entering-b",
                                topPx = 160,
                                bottomPx = 320,
                            ),
                        ),
                    viewportStartPx = 0,
                    viewportEndPx = 320,
                ),
        )

        assertTrue(
            "Expected the later row entering from below after a top delete to keep delete compensation while collapse is still in flight.",
            state.compensationFor("entering-b") != null,
        )
    }

    private fun newState(): DeleteViewportEntryCompensationState =
        DeleteViewportEntryCompensationState(
            uptimeMillis = { currentTimeMillis },
        )
}
