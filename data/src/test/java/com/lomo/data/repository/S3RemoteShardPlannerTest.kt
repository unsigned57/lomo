package com.lomo.data.repository

import com.lomo.data.sync.SyncDirectoryLayout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3RemoteShardPlanner
 * - Behavior focus: shard planning should preserve in-flight cursor continuation, prioritize unscanned cold shards so they are not starved forever, and prefer higher-risk shards by recent change rate or verification failures when freshness is otherwise tied.
 * - Observable outcomes: ordered shard bucket ids and selected active shard from planner output.
 * - Red phase: Fails before the fix because planner only sorts by lastScannedAt and original order, so equal-age shards never gain priority from higher observed change rates or verification-failure risk.
 * - Excludes: S3 transport behavior, Room SQL generation, WorkManager scheduling, and sync action application.
 */
class S3RemoteShardPlannerTest {
    private val planner = S3RemoteShardPlanner()
    private val layout = SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false)

    @Test
    fun `plan keeps in-flight cursor shard even when other shards are colder`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            shardStateStore.upsert(
                listOf(
                    shardState(bucketId = S3_SCAN_BUCKET_MEMO, relativePrefix = "lomo/memo", lastScannedAt = 800L),
                    shardState(bucketId = S3_SCAN_BUCKET_IMAGE, relativePrefix = "lomo/images", lastScannedAt = 100L),
                ),
            )

            val planned =
                planner.plan(
                    layout = layout,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState =
                        S3SyncProtocolState(
                            remoteScanCursor =
                                encodeRemoteScanCursor(
                                    StoredS3RemoteScanCursor(
                                        bucketId = S3_SCAN_BUCKET_MEMO,
                                        continuationToken = "page-2",
                                    ),
                                ),
                            scanEpoch = 7L,
                        ),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                    now = 1_000L,
                )

            assertEquals(S3_SCAN_BUCKET_MEMO, planned.activeShard?.bucketId)
            assertEquals("page-2", planned.continuationToken)
            assertEquals(7L, planned.scanEpoch)
        }

    @Test
    fun `plan prioritizes unscanned shard ahead of recently active shard`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            shardStateStore.upsert(
                listOf(
                    shardState(bucketId = S3_SCAN_BUCKET_MEMO, relativePrefix = "lomo/memo", lastScannedAt = 900L, lastChangeCount = 5),
                    shardState(bucketId = S3_SCAN_BUCKET_IMAGE, relativePrefix = "lomo/images", lastScannedAt = 850L, lastChangeCount = 4),
                ),
            )

            val planned =
                planner.plan(
                    layout = layout,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState = S3SyncProtocolState(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                    now = 1_000L,
                )

            assertEquals(S3_SCAN_BUCKET_VOICE, planned.scanPlan.firstOrNull()?.bucketId)
            assertEquals(S3_SCAN_BUCKET_VOICE, planned.activeShard?.bucketId)
        }

    @Test
    fun `plan prefers higher change rate when shard freshness is tied`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            shardStateStore.upsert(
                listOf(
                    shardState(
                        bucketId = S3_SCAN_BUCKET_MEMO,
                        relativePrefix = "lomo/memo",
                        lastScannedAt = 500L,
                        lastObjectCount = 20,
                        lastChangeCount = 1,
                    ),
                    shardState(
                        bucketId = S3_SCAN_BUCKET_IMAGE,
                        relativePrefix = "lomo/images",
                        lastScannedAt = 500L,
                        lastObjectCount = 10,
                        lastChangeCount = 9,
                    ),
                    shardState(
                        bucketId = S3_SCAN_BUCKET_VOICE,
                        relativePrefix = "lomo/voice",
                        lastScannedAt = 500L,
                        lastObjectCount = 8,
                        lastChangeCount = 0,
                    ),
                ),
            )

            val planned =
                planner.plan(
                    layout = layout,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState = S3SyncProtocolState(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                    now = 1_000L,
                )

            assertEquals(
                listOf(S3_SCAN_BUCKET_IMAGE, S3_SCAN_BUCKET_MEMO, S3_SCAN_BUCKET_VOICE),
                planned.scanPlan.take(3).map(S3RemoteScanShard::bucketId),
            )
            assertEquals(S3_SCAN_BUCKET_IMAGE, planned.activeShard?.bucketId)
        }

    @Test
    fun `plan prefers higher verification failure rate when shard freshness is tied`() =
        runTest {
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val shardStateStore = InMemoryS3RemoteShardStateStore()
            shardStateStore.upsert(
                listOf(
                    shardState(
                        bucketId = S3_SCAN_BUCKET_MEMO,
                        relativePrefix = "lomo/memo",
                        lastScannedAt = 500L,
                        lastObjectCount = 10,
                        lastChangeCount = 3,
                        lastVerificationAttemptCount = 4,
                        lastVerificationFailureCount = 0,
                    ),
                    shardState(
                        bucketId = S3_SCAN_BUCKET_IMAGE,
                        relativePrefix = "lomo/images",
                        lastScannedAt = 500L,
                        lastObjectCount = 10,
                        lastChangeCount = 1,
                        lastVerificationAttemptCount = 4,
                        lastVerificationFailureCount = 3,
                    ),
                    shardState(
                        bucketId = S3_SCAN_BUCKET_VOICE,
                        relativePrefix = "lomo/voice",
                        lastScannedAt = 500L,
                        lastObjectCount = 10,
                        lastChangeCount = 0,
                        lastVerificationAttemptCount = 1,
                        lastVerificationFailureCount = 0,
                    ),
                ),
            )

            val planned =
                planner.plan(
                    layout = layout,
                    mode = S3LocalSyncMode.Legacy(),
                    protocolState = S3SyncProtocolState(),
                    remoteIndexStore = remoteIndexStore,
                    shardStateStore = shardStateStore,
                    now = 1_000L,
                )

            assertEquals(S3_SCAN_BUCKET_IMAGE, planned.scanPlan.firstOrNull()?.bucketId)
            assertEquals(S3_SCAN_BUCKET_IMAGE, planned.activeShard?.bucketId)
        }

    private fun shardState(
        bucketId: String,
        relativePrefix: String?,
        lastScannedAt: Long,
        lastObjectCount: Int = 0,
        lastChangeCount: Int = 0,
        lastVerificationAttemptCount: Int = 0,
        lastVerificationFailureCount: Int = 0,
    ) = S3RemoteShardState(
        bucketId = bucketId,
        relativePrefix = relativePrefix,
        lastScannedAt = lastScannedAt,
        lastObjectCount = lastObjectCount,
        lastDurationMs = 10L,
        lastChangeCount = lastChangeCount,
        idleScanStreak = 0,
        lastVerificationAttemptCount = lastVerificationAttemptCount,
        lastVerificationFailureCount = lastVerificationFailureCount,
    )
}
