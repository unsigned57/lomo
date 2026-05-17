package com.lomo.data.repository


import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

/*
 * Test Contract:
 * - Unit under test: S3 large-transfer execution policy
 * - Behavior focus: large uploads/downloads should route to a dedicated large-transfer lane while small
 *   files stay on the default lane, so saturated large work does not block unrelated small transfers.
 * - Observable outcomes: selected lane for representative sync actions and whether a small transfer can
 *   complete while the large lane is fully occupied.
 * - Red phase: Fails before the fix because all actions share one weighted semaphore, so there is no
 *   separate lane classification and a saturated large-transfer lane cannot let a small transfer proceed.
 * - Excludes: AWS SDK transport behavior, executor wiring beyond lane selection, and memo refresh side effects.
 */
class S3LargeTransferPolicyTest : DataFunSpec() {
    init {
        test("small transfer routes to the small lane") { `small transfer routes to the small lane`() }

        test("large upload routes to the large lane") { `large upload routes to the large lane`() }

        test("small transfer still completes while the large lane is saturated") { `small transfer still completes while the large lane is saturated`() }
    }


    /*
     * Test Change Justification:
     * - Reason category: product contract changed.
     * - Old behavior/assertion being replaced: large transfers consumed all global action permits.
     * - Why old assertion is no longer correct: the new split-lane policy intentionally lets small transfers continue while still limiting concurrent large transfers.
     * - Coverage preserved by: the retained small-transfer assertion plus the updated large-transfer assertion that still proves heavy work is throttled above the small-file baseline.
     * - Why this is not fitting the test to the implementation: the new assertion protects the user-visible performance goal from `task.md`, not a private refactor detail.
     */
    private fun `small transfer routes to the small lane`() {
        val lane = laneForS3Action(action = smallDownloadAction(), localFiles = emptyMap(), remoteFiles = smallRemoteFiles(), metadataByPath = emptyMap())

        lane shouldBe S3ActionLane.SMALL
    }

    private fun `large upload routes to the large lane`() {
        val lane = laneForS3Action(action = largeUploadAction(), localFiles = largeLocalFiles(), remoteFiles = emptyMap(), metadataByPath = emptyMap())

        lane shouldBe S3ActionLane.LARGE
    }

    private fun `small transfer still completes while the large lane is saturated`() =
        runTest {
            val smallLane = Semaphore(1)
            val largeLane = Semaphore(1)
            largeLane.acquire()
            var smallCompleted = false

            val largeBlocked =
                async {
                    withS3ActionLanePermit(
                        action = largeUploadAction(),
                        localFiles = largeLocalFiles(),
                        remoteFiles = emptyMap(),
                        metadataByPath = emptyMap(),
                        smallLaneLimiter = smallLane,
                        largeLaneLimiter = largeLane,
                    ) {
                        "large-completed"
                    }
                }

            withS3ActionLanePermit(
                action = smallDownloadAction(),
                localFiles = emptyMap(),
                remoteFiles = smallRemoteFiles(),
                metadataByPath = emptyMap(),
                smallLaneLimiter = smallLane,
                largeLaneLimiter = largeLane,
            ) {
                smallCompleted = true
            }

            (smallCompleted).shouldBeTrue()
            (largeBlocked.isCompleted).shouldBeFalse()
            largeLane.release()
            largeBlocked.await() shouldBe "large-completed"
        }

    private fun smallDownloadAction() =
        S3SyncAction(
            path = "lomo/images/small.png",
            direction = S3SyncDirection.DOWNLOAD,
            reason = S3SyncReason.REMOTE_ONLY,
        )

    private fun largeUploadAction() =
        S3SyncAction(
            path = "lomo/images/large.png",
            direction = S3SyncDirection.UPLOAD,
            reason = S3SyncReason.LOCAL_ONLY,
        )

    private fun smallRemoteFiles() =
        mapOf(
            "lomo/images/small.png" to
                RemoteS3File(
                    path = "lomo/images/small.png",
                    etag = "etag",
                    lastModified = 10L,
                    size = S3_LARGE_TRANSFER_BYTES - 1,
                ),
        )

    private fun largeLocalFiles() =
        mapOf(
            "lomo/images/large.png" to
                LocalS3File(
                    path = "lomo/images/large.png",
                    lastModified = 10L,
                    size = S3_LARGE_TRANSFER_BYTES,
                ),
        )
}
