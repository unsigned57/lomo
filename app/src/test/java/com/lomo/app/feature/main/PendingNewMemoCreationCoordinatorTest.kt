package com.lomo.app.feature.main

import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: pending new-memo creation coordinator.
 * - Behavior focus: a submitted new-memo request must remain pending while the list is scrolling to the top, retain optional backfill timestamp metadata, reject overlap, and clear only when the matching request is consumed or canceled.
 * - Observable outcomes: exposed pending request snapshot, returned request value with timestamp metadata, overlap rejection, and matching consume/cancel behavior.
 * - Red phase: Fails before the fix because the main-screen flow has no durable pending-create state, so submitted content cannot survive the top-scroll wait as an explicit request.
 * - Excludes: Compose recomposition, LazyList animation internals, and repository persistence.
 */
class PendingNewMemoCreationCoordinatorTest : AppFunSpec() {
    init {
        test("submit stores first request and rejects overlap until consumed") {
            val coordinator = PendingNewMemoCreationCoordinator()

            val firstRequest = coordinator.submit("first memo")
            val secondRequest = coordinator.submit("second memo")

            (firstRequest) shouldBe (PendingNewMemoCreationRequest(
                    requestId = 1L,
                    content = "first memo",
                ))
            (coordinator.pendingRequest) shouldBe (firstRequest)
            (secondRequest) shouldBe null
            (coordinator.pendingRequest) shouldBe (firstRequest)
        }
    }

    init {
        test("consume clears only the matching pending request") {
            val coordinator = PendingNewMemoCreationCoordinator()
            val firstRequest = checkNotNull(coordinator.submit("first memo"))

            (coordinator.consume(requestId = firstRequest.requestId + 1L)) shouldBe null
            (coordinator.pendingRequest) shouldBe (firstRequest)
            (coordinator.consume(requestId = firstRequest.requestId)) shouldBe (firstRequest)
            (coordinator.pendingRequest) shouldBe null
        }
    }

    init {
        test("submit stores optional geo location and backfill timestamp") {
            val coordinator = PendingNewMemoCreationCoordinator()

            val request =
                coordinator.submit(
                    content = "backfilled memo",
                    geoLocation = "geo:31.2304,121.4737",
                    timestampMillis = 1_777_777_777_000L,
                )

            (request) shouldBe (PendingNewMemoCreationRequest(
                    requestId = 1L,
                    content = "backfilled memo",
                    geoLocation = "geo:31.2304,121.4737",
                    timestampMillis = 1_777_777_777_000L,
                ))
            (coordinator.pendingRequest) shouldBe (request)
        }
    }

    init {
        test("cancel clears the matching request and allows the next submit") {
            val coordinator = PendingNewMemoCreationCoordinator()
            val firstRequest = checkNotNull(coordinator.submit("first memo"))

            coordinator.cancel(requestId = firstRequest.requestId)

            val secondRequest = coordinator.submit("second memo")

            (secondRequest) shouldBe (PendingNewMemoCreationRequest(
                    requestId = firstRequest.requestId + 1L,
                    content = "second memo",
                ))
            (coordinator.pendingRequest) shouldBe (secondRequest)
        }
    }

}
