package com.lomo.data.engine

/*
 * Behavior Contract:
 * - Unit under test: NativeHandleLease.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: serialize generated-handle use under a read lease and exclusive close under a
 *   write lease without the activeReaders-before-lock deadlock.
 *
 * Scenarios:
 * - Given an open lease, when withRead runs, then the block executes and returns its value.
 * - Given close has completed, when withRead is requested, then the Kotlin boundary rejects
 *   without entering the block.
 * - Given an in-flight reader holds the read lease, when close starts, then close waits until
 *   the reader exits and only then runs the close body exactly once.
 * - Given double close, when both threads request close, then the close body runs once.
 *
 * Observable outcomes:
 * - block execution counts, close body count, and rejection after close.
 *
 * TDD proof:
 * - RED when close spins on a pre-lock reader counter while holding the write lock, or when
 *   NativeHandleLease does not exist.
 *
 * Excludes:
 * - BoltFFI engine methods, subscription handles, and invalidation queue delivery.
 */

import com.lomo.data.testing.DataFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeHandleLeaseTest : DataFunSpec() {
    init {
        test("given open lease when withRead runs then block value is returned") {
            val lease = NativeHandleLease()
            lease.withRead { 7 } shouldBe 7
        }

        test("given closed lease when withRead is requested then boundary rejects") {
            val lease = NativeHandleLease()
            val closed = lease.closeOnce { }
            closed shouldBe true
            shouldThrow<IllegalStateException> {
                lease.withRead { error("must not enter") }
            }.message shouldBe "Native engine is closed"
        }

        test("given in-flight reader when close starts then close waits for reader then runs once") {
            val lease = NativeHandleLease()
            val readerEntered = CountDownLatch(1)
            val releaseReader = CountDownLatch(1)
            val closeStarted = CountDownLatch(1)
            val closeBodyCount = AtomicInteger(0)
            val order = mutableListOf<String>()
            val orderLock = Any()

            val reader =
                Thread {
                    lease.withRead {
                        synchronized(orderLock) { order += "reader-enter" }
                        readerEntered.countDown()
                        check(releaseReader.await(2, TimeUnit.SECONDS))
                        synchronized(orderLock) { order += "reader-exit" }
                    }
                }
            reader.start()
            check(readerEntered.await(2, TimeUnit.SECONDS))

            val closer =
                Thread {
                    closeStarted.countDown()
                    val ran =
                        lease.closeOnce {
                            closeBodyCount.incrementAndGet()
                            synchronized(orderLock) { order += "close-body" }
                        }
                    ran shouldBe true
                }
            closer.start()
            check(closeStarted.await(2, TimeUnit.SECONDS))
            // Give closer a chance to block on the write lease while reader still holds read.
            Thread.sleep(30)
            closeBodyCount.get() shouldBe 0

            releaseReader.countDown()
            reader.join(2_000)
            closer.join(2_000)
            closeBodyCount.get() shouldBe 1
            synchronized(orderLock) {
                order shouldBe listOf("reader-enter", "reader-exit", "close-body")
            }
        }

        test("given concurrent close when both threads call closeOnce then body runs once") {
            val lease = NativeHandleLease()
            val bodyCount = AtomicInteger(0)
            val barrier = CyclicBarrier(2)
            val workers =
                List(2) {
                    Thread {
                        barrier.await(2, TimeUnit.SECONDS)
                        lease.closeOnce {
                            bodyCount.incrementAndGet()
                            Thread.sleep(20)
                        }
                    }
                }
            workers.forEach { it.start() }
            workers.forEach { it.join(2_000) }
            bodyCount.get() shouldBe 1
            lease.closeOnce { bodyCount.incrementAndGet() } shouldBe false
            bodyCount.get() shouldBe 1
        }
    }
}
