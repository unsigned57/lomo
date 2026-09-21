package com.lomo.app.feature.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Behavior Contract:
 * Capability: retain acknowledged UI commands within an explicit queue budget; owner: app; P1.
 * Scenarios:
 * - Given a full queue, when another command arrives, then earlier unacknowledged commands remain.
 * - Given parallel producers, when commands enqueue, then their ids are unique and ordered.
 * - Given an acknowledgement repeated twice, when consumed, then only that command is removed.
 * - Given a nonpositive capacity, when constructing the queue, then the invalid budget is rejected.
 * Observable outcomes: pending payloads/ids, capacity rejection and retained commands.
 * TDD proof: RED replaces the first pending command with the second at capacity and accepts zero;
 * GREEN: ./kotlin test --include-module=app --include-classes='com.lomo.app.feature.common.UiEventQueueCoordinatorTest'.
 * Excludes: durable command delivery across process death and Compose rendering.
 */
class UiEventQueueCoordinatorTest : FunSpec({
    test("given a full queue when enqueueing then the unacknowledged command is retained") {
        val queue = UiEventQueueCoordinator<String>(maxSize = 1)
        queue.enqueue("first").shouldBeInstanceOf<UiEventEnqueueResult.Accepted>()
        queue.enqueue("second") shouldBe UiEventEnqueueResult.Rejected(UiEventQueueRejection.CapacityReached)
        queue.events.value.map { it.payload } shouldBe listOf("first")
        val id = queue.events.value.single().id
        queue.consume(id)
        queue.consume(id)
        queue.enqueue("third")
        queue.events.value.single().id shouldBe id + 1
    }

    test("given an invalid capacity when creating a queue then construction fails") {
        shouldThrow<IllegalArgumentException> { UiEventQueueCoordinator<String>(maxSize = 0) }
    }

    test("given concurrent producers when enqueueing then every retained command has one ordered id") {
        val queue = UiEventQueueCoordinator<Int>(maxSize = 128)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(4).use { executor ->
            val results = (1..128).map { payload ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    queue.enqueue(payload)
                }
            }
            start.countDown()
            results.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        queue.events.value.map { it.id } shouldBe (1L..128L).toList()
        queue.events.value.map { it.payload }.toSet() shouldBe (1..128).toSet()
    }
})
