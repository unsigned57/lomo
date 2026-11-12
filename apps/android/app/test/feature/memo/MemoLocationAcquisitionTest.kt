package com.lomo.app.feature.memo

import android.location.Location
import com.lomo.app.testing.AppFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit

/*
 * Behavior Contract:
 * Capability: bounded foreground coordinate acquisition accepts the earliest usable fix; owner: app; P1.
 * Scenarios:
 * - Given caches using elapsed and wall clocks, when acquiring coordinates, then the freshest valid cache inserts without a live wait.
 * - Given invalid newest cache or an invalid live fix, when acquiring coordinates, then another valid fix remains eligible.
 * - Given an older usable cache, when refreshing coordinates, then the refresh wait is bounded to one second.
 * - Given a cold provider returning within the existing eight-second limit, when acquiring coordinates, then its valid fix inserts.
 * - Given cancellation or deadline expiry, when acquisition stops, then the update stream is released; revoked permission is explicit.
 * Observable outcomes: returned coordinates/failure, virtual elapsed time and active update lifetime.
 * TDD proof: target/lomo/reports/manual/memo-location/acquisition-red.log and acquisition-green-final.log.
 * RED: wrong cache chosen, invalid fix terminates, five-second cache wait; no extra cold-start wait is added.
 * Excludes: physical GNSS signal and vendor network positioning; device tests use explicit mock-provider evidence.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MemoLocationAcquisitionTest : AppFunSpec() {
    init {
        test(
            "given mixed timestamp clocks then a fresh cache inserts immediately instead of choosing an older wall-clock fix",
        ) {
            runTest {
                val startedAt = testScheduler.currentTime
                val fresh = location(31.2304, ELAPSED_NOW_MS - 1_000, WALL_NOW_MS - 1_000)
                val old = location(30.0, 0, WALL_NOW_MS - 600_000)
                val result = acquireMemoLocation(
                    listOf(fresh, old),
                    flow { awaitCancellation() },
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result.shouldBeInstanceOf<MemoLocationResult.Located>().coordinates.latitude shouldBe 31.2304
                testScheduler.currentTime - startedAt shouldBe 0L
            }
        }
        test("given an invalid latest cache then an older valid fresh cache is used immediately") {
            runTest {
                val startedAt = testScheduler.currentTime
                val valid = location(31.2304, ELAPSED_NOW_MS - 10_000, WALL_NOW_MS - 10_000)
                val invalid = location(Double.NaN, ELAPSED_NOW_MS, WALL_NOW_MS)
                val result = acquireMemoLocation(
                    listOf(valid, invalid),
                    flow { awaitCancellation() },
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result.shouldBeInstanceOf<MemoLocationResult.Located>().coordinates.latitude shouldBe 31.2304
                testScheduler.currentTime - startedAt shouldBe 0L
            }
        }
        test("given a usable older cache and no live fix then refresh returns the cache within one second") {
            runTest {
                val startedAt = testScheduler.currentTime
                var collecting = false
                val old = location(31.2304, ELAPSED_NOW_MS - 600_000, WALL_NOW_MS - 600_000)
                val updates = flow<Location> {
                    collecting = true
                    try {
                        awaitCancellation()
                    } finally {
                        collecting = false
                    }
                }
                val result = acquireMemoLocation(
                    listOf(old),
                    updates,
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result.shouldBeInstanceOf<MemoLocationResult.Located>().coordinates.latitude shouldBe 31.2304
                testScheduler.currentTime - startedAt shouldBe 1_000L
                collecting shouldBe false
            }
        }
        test(
            "given a cold provider with a seven-second fix then valid coordinates insert within the unchanged deadline",
        ) {
            runTest {
                val startedAt = testScheduler.currentTime
                val updates = flow {
                    delay(7_000)
                    emit(location(31.2304, ELAPSED_NOW_MS + 7_000, WALL_NOW_MS + 7_000))
                }
                val result = acquireMemoLocation(
                    emptyList(),
                    updates,
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result.shouldBeInstanceOf<MemoLocationResult.Located>().coordinates.latitude shouldBe 31.2304
                testScheduler.currentTime - startedAt shouldBe 7_000L
            }
        }
        test("given an invalid first live fix then another provider's valid fix can still insert") {
            runTest {
                val startedAt = testScheduler.currentTime
                val updates = flow {
                    emit(location(Double.NaN, ELAPSED_NOW_MS, WALL_NOW_MS))
                    delay(100)
                    emit(location(31.2304, ELAPSED_NOW_MS + 100, WALL_NOW_MS + 100))
                }
                val result = acquireMemoLocation(
                    emptyList(),
                    updates,
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result.shouldBeInstanceOf<MemoLocationResult.Located>().coordinates.latitude shouldBe 31.2304
                testScheduler.currentTime - startedAt shouldBe 100L
            }
        }
        test("given no fix when the existing deadline expires then the update stream is released") {
            runTest {
                val startedAt = testScheduler.currentTime
                var collecting = false
                val updates = flow<Location> {
                    collecting = true
                    try {
                        awaitCancellation()
                    } finally {
                        collecting = false
                    }
                }
                val result = acquireMemoLocation(
                    emptyList(),
                    updates,
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result shouldBe MemoLocationResult.Failed(MemoLocationFailure.TimedOut)
                testScheduler.currentTime - startedAt shouldBe 8_000L
                collecting shouldBe false
            }
        }
        test("given the editor request is cancelled then active location collection ends immediately") {
            runTest {
                val startedAt = testScheduler.currentTime
                var collecting = false
                val updates = flow<Location> {
                    collecting = true
                    try {
                        awaitCancellation()
                    } finally {
                        collecting = false
                    }
                }
                val request = launch {
                    acquireMemoLocation(
                        emptyList(),
                        updates,
                        { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                        { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                    )
                }
                runCurrent()
                collecting shouldBe true
                request.cancelAndJoin()
                request.isCancelled shouldBe true
                collecting shouldBe false
                testScheduler.currentTime - startedAt shouldBe 0L
            }
        }
        test(
            "given location permission is revoked during acquisition then a permission failure returns without waiting",
        ) {
            runTest {
                val startedAt = testScheduler.currentTime
                val result = acquireMemoLocation(
                    emptyList(),
                    flow { throw SecurityException("location permission revoked") },
                    { TimeUnit.MILLISECONDS.toNanos(ELAPSED_NOW_MS + testScheduler.currentTime - startedAt) },
                    { WALL_NOW_MS + testScheduler.currentTime - startedAt },
                )
                result shouldBe MemoLocationResult.Failed(MemoLocationFailure.PermissionDenied)
                testScheduler.currentTime - startedAt shouldBe 0L
            }
        }
    }
}

private const val ELAPSED_NOW_MS = 3_600_000L
private const val WALL_NOW_MS = 1_700_000_000_000L

private fun location(latitude: Double, elapsedMillis: Long, wallMillis: Long): Location = mockk {
    every { this@mockk.latitude } returns latitude
    every { longitude } returns 121.4737
    every { elapsedRealtimeNanos } returns TimeUnit.MILLISECONDS.toNanos(elapsedMillis)
    every { time } returns wallMillis
}
