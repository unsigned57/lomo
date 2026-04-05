package com.lomo.app.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: pending new-memo creation coordinator.
 * - Behavior focus: a submitted new-memo request must remain pending while the list is scrolling to the top, reject overlap, and clear only when the matching request is consumed or canceled.
 * - Observable outcomes: exposed pending request snapshot, returned request value, overlap rejection, and matching consume/cancel behavior.
 * - Red phase: Fails before the fix because the main-screen flow has no durable pending-create state, so submitted content cannot survive the top-scroll wait as an explicit request.
 * - Excludes: Compose recomposition, LazyList animation internals, and repository persistence.
 */
class PendingNewMemoCreationCoordinatorTest {
    @Test
    fun `submit stores first request and rejects overlap until consumed`() {
        val coordinator = PendingNewMemoCreationCoordinator()

        val firstRequest = coordinator.submit("first memo")
        val secondRequest = coordinator.submit("second memo")

        assertEquals(
            PendingNewMemoCreationRequest(
                requestId = 1L,
                content = "first memo",
            ),
            firstRequest,
        )
        assertEquals(firstRequest, coordinator.pendingRequest)
        assertNull(secondRequest)
        assertEquals(firstRequest, coordinator.pendingRequest)
    }

    @Test
    fun `consume clears only the matching pending request`() {
        val coordinator = PendingNewMemoCreationCoordinator()
        val firstRequest = checkNotNull(coordinator.submit("first memo"))

        assertNull(coordinator.consume(requestId = firstRequest.requestId + 1L))
        assertEquals(firstRequest, coordinator.pendingRequest)
        assertEquals(firstRequest, coordinator.consume(requestId = firstRequest.requestId))
        assertNull(coordinator.pendingRequest)
    }

    @Test
    fun `cancel clears the matching request and allows the next submit`() {
        val coordinator = PendingNewMemoCreationCoordinator()
        val firstRequest = checkNotNull(coordinator.submit("first memo"))

        coordinator.cancel(requestId = firstRequest.requestId)

        val secondRequest = coordinator.submit("second memo")

        assertEquals(
            PendingNewMemoCreationRequest(
                requestId = firstRequest.requestId + 1L,
                content = "second memo",
            ),
            secondRequest,
        )
        assertEquals(secondRequest, coordinator.pendingRequest)
    }
}
