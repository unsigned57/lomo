package com.lomo.data.repository

import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.S3RemoteObject
import com.lomo.domain.model.S3RemoteVerificationLevel
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: S3 prepared-action verification gate
 * - Behavior focus: destructive or overwrite-capable actions should be re-planned after targeted HeadObject verification so stale cached remote state cannot survive into apply-time as a skipped destructive action.
 * - Observable outcomes: rewritten S3SyncPlan action direction/reason, verified remote file state, and confirmed-missing path set.
 * - Red phase: Fails before the fix because the executor verification phase is a no-op, leaving stale cached DELETE_REMOTE / UPLOAD actions to be skipped later during apply while the sync result still reports the original destructive outcome.
 * - Excludes: conflict payload downloads, Room persistence, and WorkManager scheduling.
 */
class S3PreparedActionVerificationGateTest {
    private val planner = S3SyncPlanner(timestampToleranceMs = 0L)
    private val encodingSupport = S3SyncEncodingSupport()
    private val config =
        S3ResolvedConfig(
            endpointUrl = "https://s3.example.com",
            region = "us-east-1",
            bucket = "bucket",
            prefix = "",
            accessKeyId = "access",
            secretAccessKey = "secret",
            sessionToken = null,
            pathStyle = com.lomo.domain.model.S3PathStyle.PATH_STYLE,
            encryptionMode = com.lomo.domain.model.S3EncryptionMode.NONE,
            encryptionPassword = null,
        )

    @Test
    fun `verify rewrites stale cached delete-remote candidate into download before apply`() =
        runTest {
            val path = "lomo/memo/note.md"
            val metadata =
                S3SyncMetadataEntity(
                    relativePath = path,
                    remotePath = path,
                    etag = "etag-old",
                    remoteLastModified = 10L,
                    localLastModified = 10L,
                    lastSyncedAt = 10L,
                    lastResolvedDirection = S3SyncMetadataEntity.NONE,
                    lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
                )
            val prepared =
                PreparedS3Sync(
                    layout = com.lomo.data.sync.SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    localFiles = emptyMap(),
                    remoteFiles =
                        mapOf(
                            path to
                                RemoteS3File(
                                    path = path,
                                    etag = "etag-old",
                                    lastModified = 10L,
                                    remotePath = path,
                                    verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                                ),
                        ),
                    metadataByPath = mapOf(path to metadata),
                    plan =
                        S3SyncPlan(
                            actions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_REMOTE, S3SyncReason.LOCAL_DELETED)),
                            pendingChanges = 1,
                        ),
                    normalActions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_REMOTE, S3SyncReason.LOCAL_DELETED)),
                    conflictSet = null,
                    completeSnapshot = false,
                    protocolState = S3SyncProtocolState(scanEpoch = 7L),
                    remoteFileCountHint = 1,
                )
            val verifier = S3PreparedActionVerificationGate(planner = planner, encodingSupport = encodingSupport)
            val client =
                VerificationProbeS3Client(
                    onHead = { key ->
                        assertEquals(path, key)
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-new",
                            lastModified = 20L,
                            size = 1L,
                            metadata = emptyMap(),
                        )
                    },
                )

            val verified = verifier.verify(prepared = prepared, client = client, config = config)

            assertEquals(
                listOf(S3SyncAction(path, S3SyncDirection.DOWNLOAD, S3SyncReason.REMOTE_NEWER)),
                verified.prepared.plan.actions,
            )
            assertEquals(
                S3RemoteVerificationLevel.VERIFIED_REMOTE,
                verified.prepared.remoteFiles[path]?.verificationLevel,
            )
            assertTrue(verified.verifiedMissingRemotePaths.isEmpty())
            assertEquals(listOf(path), client.headKeys)
        }

    @Test
    fun `verify does not delete local file on first observed remote miss outside reconcile evidence`() =
        runTest {
            val path = "lomo/memo/note.md"
            val metadata =
                S3SyncMetadataEntity(
                    relativePath = path,
                    remotePath = path,
                    etag = "etag-stable",
                    remoteLastModified = 10L,
                    localLastModified = 10L,
                    lastSyncedAt = 10L,
                    lastResolvedDirection = S3SyncMetadataEntity.NONE,
                    lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
                )
            val prepared =
                PreparedS3Sync(
                    layout = com.lomo.data.sync.SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    localFiles = mapOf(path to LocalS3File(path = path, lastModified = 10L)),
                    remoteFiles = emptyMap(),
                    metadataByPath = mapOf(path to metadata),
                    plan =
                        S3SyncPlan(
                            actions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                            pendingChanges = 1,
                        ),
                    normalActions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                    conflictSet = null,
                    completeSnapshot = false,
                    protocolState = S3SyncProtocolState(scanEpoch = 11L),
                    remoteFileCountHint = 0,
                )
            val verifier =
                S3PreparedActionVerificationGate(
                    planner = planner,
                    encodingSupport = encodingSupport,
                    remoteIndexStore = InMemoryS3RemoteIndexStore(),
                )
            val client = VerificationProbeS3Client(onHead = { null })

            val verified = verifier.verify(prepared = prepared, client = client, config = config)

            assertEquals(emptyList<S3SyncAction>(), verified.prepared.plan.actions)
            assertTrue(verified.verifiedMissingRemotePaths.isEmpty())
            assertEquals(setOf(path), verified.prepared.observedMissingRemotePaths)
            assertEquals(listOf(path), client.headKeys)
        }

    @Test
    fun `verify preserves delete local after repeated verified missing evidence`() =
        runTest {
            val path = "lomo/memo/note.md"
            val metadata =
                S3SyncMetadataEntity(
                    relativePath = path,
                    remotePath = path,
                    etag = "etag-stable",
                    remoteLastModified = 10L,
                    localLastModified = 10L,
                    lastSyncedAt = 10L,
                    lastResolvedDirection = S3SyncMetadataEntity.NONE,
                    lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
                )
            val prepared =
                PreparedS3Sync(
                    layout = com.lomo.data.sync.SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    localFiles = mapOf(path to LocalS3File(path = path, lastModified = 10L)),
                    remoteFiles = emptyMap(),
                    metadataByPath = mapOf(path to metadata),
                    plan =
                        S3SyncPlan(
                            actions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                            pendingChanges = 1,
                        ),
                    normalActions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                    conflictSet = null,
                    completeSnapshot = false,
                    protocolState = S3SyncProtocolState(scanEpoch = 12L),
                    remoteFileCountHint = 0,
                )
            val remoteIndexStore =
                InMemoryS3RemoteIndexStore().apply {
                    upsert(
                        listOf(
                            missingRemoteIndexEntry(
                                relativePath = path,
                                remotePath = path,
                                now = 100L,
                                scanEpoch = 12L,
                            ),
                        ),
                    )
                }
            val verifier =
                S3PreparedActionVerificationGate(
                    planner = planner,
                    encodingSupport = encodingSupport,
                    remoteIndexStore = remoteIndexStore,
                )
            val client = VerificationProbeS3Client(onHead = { null })

            val verified = verifier.verify(prepared = prepared, client = client, config = config)

            assertEquals(
                listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                verified.prepared.plan.actions,
            )
            assertEquals(setOf(path), verified.verifiedMissingRemotePaths)
            assertTrue(verified.prepared.observedMissingRemotePaths.isEmpty())
            assertTrue(client.headKeys.isEmpty())
        }

    @Test
    fun `verify skips delete-local head request when missing evidence is already stable`() =
        runTest {
            val path = "lomo/memo/note.md"
            val metadata =
                S3SyncMetadataEntity(
                    relativePath = path,
                    remotePath = path,
                    etag = "etag-stable",
                    remoteLastModified = 10L,
                    localLastModified = 10L,
                    lastSyncedAt = 10L,
                    lastResolvedDirection = S3SyncMetadataEntity.NONE,
                    lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
                )
            val prepared =
                PreparedS3Sync(
                    layout = com.lomo.data.sync.SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    localFiles = mapOf(path to LocalS3File(path = path, lastModified = 10L)),
                    remoteFiles = emptyMap(),
                    metadataByPath = mapOf(path to metadata),
                    plan =
                        S3SyncPlan(
                            actions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                            pendingChanges = 1,
                        ),
                    normalActions = listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                    conflictSet = null,
                    completeSnapshot = false,
                    protocolState = S3SyncProtocolState(scanEpoch = 13L),
                    remoteFileCountHint = 0,
                )
            val remoteIndexStore =
                InMemoryS3RemoteIndexStore().apply {
                    upsert(
                        listOf(
                            missingRemoteIndexEntry(
                                relativePath = path,
                                remotePath = path,
                                now = 100L,
                                scanEpoch = 13L,
                            ),
                        ),
                    )
                }
            val verifier =
                S3PreparedActionVerificationGate(
                    planner = planner,
                    encodingSupport = encodingSupport,
                    remoteIndexStore = remoteIndexStore,
                )
            val client =
                VerificationProbeS3Client(
                    onHead = {
                        error("stable missing evidence should skip delete-local HEAD verification")
                    },
                )

            val verified = verifier.verify(prepared = prepared, client = client, config = config)

            assertEquals(
                listOf(S3SyncAction(path, S3SyncDirection.DELETE_LOCAL, S3SyncReason.REMOTE_DELETED)),
                verified.prepared.plan.actions,
            )
            assertEquals(setOf(path), verified.verifiedMissingRemotePaths)
            assertTrue(client.headKeys.isEmpty())
        }

    @Test
    fun `verify checks multiple candidates concurrently`() =
        runTest {
            val paths =
                listOf(
                    "lomo/memo/alpha.md",
                    "lomo/memo/bravo.md",
                    "lomo/memo/charlie.md",
                )
            val actions =
                paths.map { path ->
                    S3SyncAction(path, S3SyncDirection.DELETE_REMOTE, S3SyncReason.LOCAL_DELETED)
                }
            val prepared =
                PreparedS3Sync(
                    layout = com.lomo.data.sync.SyncDirectoryLayout(memoFolder = "memo", imageFolder = "images", voiceFolder = "voice", allSameDirectory = false),
                    localFiles = emptyMap(),
                    remoteFiles =
                        paths.associateWith { path ->
                            RemoteS3File(
                                path = path,
                                etag = "etag-$path",
                                lastModified = 10L,
                                remotePath = path,
                                verificationLevel = S3RemoteVerificationLevel.INDEX_CACHED_REMOTE,
                            )
                        },
                    metadataByPath =
                        paths.associateWith { path ->
                            S3SyncMetadataEntity(
                                relativePath = path,
                                remotePath = path,
                                etag = "etag-$path",
                                remoteLastModified = 10L,
                                localLastModified = 10L,
                                lastSyncedAt = 10L,
                                lastResolvedDirection = S3SyncMetadataEntity.NONE,
                                lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
                            )
                        },
                    plan = S3SyncPlan(actions = actions, pendingChanges = actions.size),
                    normalActions = actions,
                    conflictSet = null,
                    completeSnapshot = false,
                    protocolState = S3SyncProtocolState(scanEpoch = 21L),
                    remoteFileCountHint = actions.size,
                )
            val inFlight = AtomicInteger(0)
            val peakConcurrency = AtomicInteger(0)
            val client =
                VerificationProbeS3Client(
                    onHead = { key ->
                        val concurrent = inFlight.incrementAndGet()
                        peakConcurrency.accumulateAndGet(concurrent, ::maxOf)
                        delay(25)
                        inFlight.decrementAndGet()
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-$key",
                            lastModified = 20L,
                            size = 1L,
                            metadata = emptyMap(),
                        )
                    },
                )

            S3PreparedActionVerificationGate(planner = planner, encodingSupport = encodingSupport)
                .verify(prepared = prepared, client = client, config = config)

            assertEquals(paths, client.headKeys.sorted())
            assertTrue(
                "Verification should overlap HeadObject requests for independent paths instead of serializing them one by one.",
                peakConcurrency.get() > 1,
            )
        }
}

private class VerificationProbeS3Client(
    private val onHead: suspend (String) -> S3RemoteObject?,
) : com.lomo.data.s3.LomoS3Client {
    val headKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headKeys += key
        return onHead(key)
    }

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = emptyList()

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): com.lomo.data.s3.S3RemoteListPage =
        com.lomo.data.s3.S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)

    override suspend fun getObject(key: String): com.lomo.data.s3.S3RemoteObjectPayload {
        error("verification gate test should not download objects")
    }

    override suspend fun putObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): com.lomo.data.s3.S3PutObjectResult {
        error("verification gate test should not upload objects")
    }

    override suspend fun deleteObject(key: String) {
        error("verification gate test should not delete objects")
    }

    override fun close() = Unit
}
