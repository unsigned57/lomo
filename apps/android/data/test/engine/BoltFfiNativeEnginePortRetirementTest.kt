package com.lomo.data.engine

import com.lomo.data.engine.lan.LanInboxWait
import com.lomo.data.testing.DataFunSpec
import com.lomo.nativebridge.LanInboxWaitDto
import com.lomo.nativebridge.LanServicePhaseDto
import com.lomo.nativebridge.LanServiceSnapshotDto
import com.lomo.nativebridge.LomoEngine
import com.lomo.nativebridge.ShutdownOutcome
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Behavior Contract:
 * Capability: retiring a native handle wakes its long-lived inbox reader before draining leases.
 * Scenarios:
 * Given an inbox call parked until service stop, when close starts, then the service is stopped,
 * the read exits, and shutdown/handle release finish without waiting for the inbox timeout.
 * Observable outcomes: both futures finish under a bounded deadline; the closed port rejects reads.
 * TDD proof: focused RED times out in close because stop/shutdown are behind the exclusive lease.
 * Excludes: Rust condvar implementation, network traffic and workspace mutation ordering.
 */
class BoltFfiNativeEnginePortRetirementTest : DataFunSpec() {
    init {
        test("given a parked inbox wait when the port closes then retirement wakes the reader") {
            val entered = CountDownLatch(1)
            val wake = CountDownLatch(1)
            val engine = mockk<LomoEngine>()
            every { engine.awaitLanInbox(0uL, 30_000uL) } answers {
                entered.countDown()
                check(wake.await(5, TimeUnit.SECONDS))
                LanInboxWaitDto(0uL, null, 0uL, null)
            }
            every { engine.stopLanService() } answers {
                wake.countDown()
                LanServiceSnapshotDto(LanServicePhaseDto.STOPPED, null)
            }
            every { engine.shutdown(any()) } returns ShutdownOutcome.COMPLETED
            every { engine.close() } returns Unit
            val port = BoltFfiNativeEnginePort(engine)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val reader = workers.submit<LanInboxWait> { port.awaitLanInbox(0uL, 30_000uL) }
                check(entered.await(2, TimeUnit.SECONDS))
                val close = workers.submit { port.close() }
                close.get(1, TimeUnit.SECONDS)
                reader.get(1, TimeUnit.SECONDS).generation shouldBe 0uL
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> { port.state() }
            } finally {
                wake.countDown()
                workers.shutdown()
                check(workers.awaitTermination(5, TimeUnit.SECONDS))
                port.close()
            }
        }
    }
}
