package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: BoundedInvalidationQueue.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: deliver native core events only as bounded, conflated invalidations after the
 *   enqueueing callback returns, and never after stop.
 *
 * Scenarios:
 * - Given a listener that blocks until released, when enqueue returns, then the caller is not
 *   blocked inside the consumer and delivery has not yet started on the caller thread.
 * - Given several events are enqueued, when the drain runs, then every event is delivered on the
 *   queue executor thread rather than the producer thread.
 * - Given the queue is full, when further events arrive, then delivery continues via a single
 *   conflated overflow invalidation carrying the latest sequence instead of growing unbounded.
 * - Given the queue is stopped, when further events are enqueued or drain is in flight, then no
 *   consumer delivery is observed after stop.
 *
 * Observable outcomes:
 * - delivery count, delivered sequences, delivering thread identity, and post-stop silence.
 *
 * TDD proof:
 * - RED before BoundedInvalidationQueue exists or when callback path still invokes the consumer
 *   synchronously on the producer thread.
 *
 * Excludes:
 * - BoltFFI generated classes, LomoEngine handles, and domain readiness mapping.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BoundedInvalidationQueueTest : DataFunSpec() {
    init {
        test("given blocking consumer when enqueue returns then producer is not inside consumer") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val producerInConsumer = AtomicInteger(0)
            val producer = Thread.currentThread()
            val queue =
                BoundedInvalidationQueue { _ ->
                    if (Thread.currentThread() === producer) {
                        producerInConsumer.incrementAndGet()
                    }
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                }

            try {
                queue.enqueue(NativeCoreEvent(coreRevision = 1uL, eventSequence = 1uL))
                // Enqueue must return without running the consumer on this thread.
                producerInConsumer.get() shouldBe 0
                check(entered.await(2, TimeUnit.SECONDS)) { "drain never reached consumer" }
                producerInConsumer.get() shouldBe 0
            } finally {
                release.countDown()
                queue.close()
            }
        }

        test("given multiple events when drained then delivery runs on executor not producer") {
            val delivered = CopyOnWriteArrayList<Pair<ULong, String>>()
            val done = CountDownLatch(3)
            val producerName = Thread.currentThread().name
            val queue =
                BoundedInvalidationQueue { event ->
                    delivered += event.eventSequence to Thread.currentThread().name
                    done.countDown()
                }

            try {
                queue.enqueue(NativeCoreEvent(1uL, 1uL))
                queue.enqueue(NativeCoreEvent(1uL, 2uL))
                queue.enqueue(NativeCoreEvent(1uL, 3uL))
                check(done.await(2, TimeUnit.SECONDS)) { "expected three deliveries, got ${delivered.size}" }
                delivered.map { it.first } shouldBe listOf(1uL, 2uL, 3uL)
                delivered.forEach { (_, threadName) ->
                    threadName shouldBe "lomo-native-invalidation"
                }
                delivered.map { it.second }.shouldHaveSize(3)
                delivered.map { it.second }.none { it == producerName } shouldBe true
            } finally {
                queue.close()
            }
        }

        test("given full queue when more events arrive then latest overflow is conflated not dropped silently") {
            val capacity = 4
            val hold = CountDownLatch(1)
            val firstEntered = CountDownLatch(1)
            val delivered = CopyOnWriteArrayList<ULong>()
            val queue =
                BoundedInvalidationQueue(capacity = capacity, deliver = { event ->
                    delivered += event.eventSequence
                    if (event.eventSequence == 1uL) {
                        firstEntered.countDown()
                        check(hold.await(2, TimeUnit.SECONDS))
                    }
                })

            try {
                queue.enqueue(NativeCoreEvent(1uL, 1uL))
                check(firstEntered.await(2, TimeUnit.SECONDS))
                // Fill remaining capacity while first delivery is held.
                for (seq in 2uL..(capacity.toULong() + 3uL)) {
                    queue.enqueue(NativeCoreEvent(1uL, seq))
                }
                hold.countDown()
                // Wait until drain settles: at least capacity deliveries, and latest sequence present.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline &&
                    (delivered.size < capacity || !delivered.contains((capacity + 3).toULong()))
                ) {
                    Thread.sleep(5)
                }
                delivered.size shouldBeGreaterThanOrEqual capacity
                delivered shouldContain (capacity + 3).toULong()
            } finally {
                hold.countDown()
                queue.close()
            }
        }

        test("given stopped queue when events are enqueued then consumer is never called") {
            val deliveries = AtomicInteger(0)
            val queue = BoundedInvalidationQueue { deliveries.incrementAndGet() }
            queue.stop()
            queue.enqueue(NativeCoreEvent(9uL, 9uL))
            Thread.sleep(50)
            deliveries.get() shouldBe 0
            queue.close()
        }

        test("given in-flight drain when stop races then post-stop deliveries are suppressed") {
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val postStop = AtomicReference<ULong?>(null)
            val queue =
                BoundedInvalidationQueue { event ->
                    started.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    // If stop already ran, this delivery must not be observed by the assertion below.
                    // The queue itself must refuse scheduling after stop; this records any leak.
                    if (event.eventSequence == 99uL) {
                        postStop.set(event.eventSequence)
                    }
                }

            try {
                queue.enqueue(NativeCoreEvent(1uL, 1uL))
                check(started.await(2, TimeUnit.SECONDS))
                queue.stop()
                queue.enqueue(NativeCoreEvent(1uL, 99uL))
                release.countDown()
                Thread.sleep(50)
                postStop.get() shouldBe null
            } finally {
                release.countDown()
                queue.close()
            }
        }
    }
}
