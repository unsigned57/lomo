package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncFile
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncResult
import com.lomo.domain.model.S3SyncState
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictResolution
import com.lomo.domain.model.SyncConflictResolutionChoice
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewResolution
import com.lomo.domain.model.SyncReviewResolutionChoice
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

/*
 * Behavior Contract:
 * - Unit under test: S3ConflictResolver
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: apply explicit S3 conflict-resolution choices without whole-bucket scans while preserving conflicts that cannot be safely resolved.
 *
 * Scenarios:
 * - Given indexed metadata, when KEEP_LOCAL or KEEP_REMOTE is chosen, then the resolver uses the indexed object path without remote listing.
 * - Given MERGE_TEXT can merge memo content, when resolution runs, then the merged memo is written locally and uploaded once.
 * - Given MERGE_TEXT exceeds the text-merge budget, when resolution runs, then the conflict remains pending and no local write or upload occurs.
 * - Given incremental S3 stores already contain protocol, journal, and remote-index state, when every MERGE_TEXT conflict is declined by the merge budget, then only pending conflict/UI state changes.
 * - Given SKIP_FOR_NOW is chosen for a file, when other files are resolved, then skipped files remain pending for review.
 * - Given remote IO fails, when resolution runs, then the failure is mapped to an S3 error result.
 *
 * Observable outcomes:
 * - S3SyncResult subtype, pending conflict store contents, state-holder value, remote list/get/put targets, protocol state, journal state, remote index state, metadata persistence, local content, and memo refresh side effects.
 *
 * TDD proof:
 * - RED: the MERGE_TEXT budget scenario returned S3SyncResult.Error("Unable to merge conflict...") instead of preserving a pending conflict.
 * - RED follow-up-2 command: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3ConflictResolverTest'
 * - RED follow-up-2 symptom: resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state failed because protocolStateStore.read() differed from the baseline; lastSuccessfulSyncAt/lastFastSyncAt were updated even though no file resolved.
 * - GREEN follow-up-2 worker reported: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3ConflictResolverTest' --tests 'com.lomo.data.git.GitSyncEngineConflictTest' -> BUILD SUCCESSFUL in 44s.
 * - GREEN WebDav regression worker reported: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.WebDavConflictResolverTest' -> BUILD SUCCESSFUL in 18s.
 *
 * Excludes:
 * - AWS SDK transport details, planner internals, metadata persistence internals, and UI rendering.
 */
class S3ConflictResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        test("resolveConflicts keeps remote using indexed remote path without remote listing") { `resolveConflicts keeps remote using indexed remote path without remote listing`() }

        test("resolveConflicts keeps remote using remote index path when metadata snapshot is missing") { `resolveConflicts keeps remote using remote index path when metadata snapshot is missing`() }

        test("resolveConflicts keeps local using indexed remote path without remote listing") { `resolveConflicts keeps local using indexed remote path without remote listing`() }

        test("resolveConflicts MERGE_TEXT writes merged memo locally and uploads it without remote listing") { `resolveConflicts MERGE_TEXT writes merged memo locally and uploads it without remote listing`() }

        test("resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state") { `resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state`() }

        test("resolveConflicts rolls back metadata, remote index, and journal when final protocol commit fails") { `resolveConflicts rolls back metadata, remote index, and journal when final protocol commit fails`() }

        test("resolveConflicts maps remote read failure without bucket scan") { `resolveConflicts maps remote read failure without bucket scan`() }

        test("resolveConflicts keeps skipped files pending and returns conflict state") { `resolveConflicts keeps skipped files pending and returns conflict state`() }

        test("resolveReview invalidates pending descriptor before applying choices") {
            `resolveReview invalidates pending descriptor before applying choices`()
        }

        test("resolveReview meters pending descriptor remote validation") {
            `resolveReview meters pending descriptor remote validation`()
        }
    }


    @MockK(relaxed = true)
    private lateinit var dataStore: LomoDataStore

    @MockK(relaxed = true)
    private lateinit var credentialStore: S3CredentialStore

    @MockK(relaxed = true)
    private lateinit var clientFactory: LomoS3ClientFactory

    @MockK(relaxed = true)
    private lateinit var markdownStorageDataSource: MarkdownStorageDataSource

    @MockK(relaxed = true)
    private lateinit var localMediaSyncStore: LocalMediaSyncStore

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    private lateinit var stateHolder: S3SyncStateHolder
    private lateinit var support: S3SyncRepositorySupport
    private lateinit var fileBridge: S3SyncFileBridge

    private fun setUp() {
        MockKAnnotations.init(this)

        every { dataStore.s3SyncEnabled } returns flowOf(true)
        every { dataStore.s3EndpointUrl } returns flowOf("https://s3.example.com")
        every { dataStore.s3Region } returns flowOf("us-east-1")
        every { dataStore.s3Bucket } returns flowOf("bucket")
        every { dataStore.s3Prefix } returns flowOf("prefix")
        every { dataStore.s3PathStyle } returns flowOf("path_style")
        every { dataStore.s3EncryptionMode } returns flowOf("none")
        every { dataStore.s3RcloneFilenameEncryption } returns flowOf("standard")
        every { dataStore.s3RcloneFilenameEncoding } returns flowOf("base64")
        every { dataStore.s3RcloneDirectoryNameEncryption } returns flowOf(true)
        every { dataStore.s3RcloneDataEncryptionEnabled } returns flowOf(true)
        every { dataStore.s3RcloneEncryptedSuffix } returns flowOf(".bin")
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
        every { dataStore.rootDirectory } returns flowOf("/memo")
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf("/images")
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf("/voice")
        every { dataStore.voiceUri } returns flowOf(null)
        every { credentialStore.getAccessKeyId() } returns "access"
        every { credentialStore.getSecretAccessKey() } returns "secret"
        every { credentialStore.getSessionToken() } returns null
        every { credentialStore.getEncryptionPassword() } returns null
        every { credentialStore.getEncryptionPassword2() } returns null
        coEvery { memoSynchronizer.refreshImportedSync() } returns Unit
        coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()

        stateHolder = S3SyncStateHolder()
    }

    private fun `resolveConflicts keeps remote using indexed remote path without remote listing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 40L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(),
                        )
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# remote",
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 40L)
            val resolver = createResolver(client = client, metadataDao = metadataDao)

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.getObjectKeys shouldBe listOf(remotePath)
            client.listCalls shouldBe 0
            coVerify(exactly = 1) { memoSynchronizer.refreshImportedSync() }
            metadataDao.require(path).remotePath shouldBe remotePath
            (stateHolder.state.value is S3SyncState.Success).shouldBeTrue()
        }

    private fun `resolveConflicts keeps remote using remote index path when metadata snapshot is missing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 40L,
                            metadata = emptyMap(),
                            bytes = "# remote".toByteArray(),
                        )
                    },
                )
            coEvery {
                markdownStorageDataSource.saveFileIn(
                    directory = MemoDirectoryType.MAIN,
                    filename = "note.md",
                    content = "# remote",
                    append = false,
                    uri = null,
                )
            } returns null
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 40L)
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            remoteIndexStore.upsert(
                listOf(
                    S3RemoteIndexEntry(
                        relativePath = path,
                        remotePath = remotePath,
                        etag = "etag-remote",
                        remoteLastModified = 40L,
                        size = 1L,
                        lastSeenAt = 40L,
                        lastVerifiedAt = 40L,
                        scanBucket = S3_SCAN_BUCKET_MEMO,
                    ),
                ),
            )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    remoteIndexStore = remoteIndexStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.getObjectKeys shouldBe listOf(remotePath)
            client.listCalls shouldBe 0
            (stateHolder.state.value is S3SyncState.Success).shouldBeTrue()
        }

    private fun `resolveConflicts keeps local using indexed remote path without remote listing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onPutObject = { key, _ ->
                        key shouldBe remotePath
                        S3PutObjectResult(eTag = "etag-uploaded")
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 50L)
            val resolver = createResolver(client = client, metadataDao = metadataDao)

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.putKeys shouldBe listOf(remotePath)
            client.listCalls shouldBe 0
            coVerify(exactly = 1) { memoSynchronizer.refreshImportedSync() }
            metadataDao.require(path).remotePath shouldBe remotePath
        }

    private fun `resolveConflicts MERGE_TEXT writes merged memo locally and uploads it without remote listing`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val merged = "start\nlocal\nmiddle\nremote\nend"
            val client =
                ConflictProbeS3Client(
                    onPutObject = { key, bytes ->
                        key shouldBe remotePath
                        bytes.toString(Charsets.UTF_8) shouldBe merged
                        S3PutObjectResult(eTag = "etag-merged")
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            val markdownStorageDataSource =
                RecordingMarkdownStorageDataSource(metadataLastModified = 60L)
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = metadataDao,
                    markdownStorageDataSource = markdownStorageDataSource,
                )
            val conflictSet =
                conflictSet(
                    path = path,
                    localContent = "start\nlocal\nmiddle\nend",
                    remoteContent = "start\nmiddle\nremote\nend",
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.MERGE_TEXT),
                        ),
                    conflictSet = conflictSet,
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.putKeys shouldBe listOf(remotePath)
            client.listCalls shouldBe 0
            markdownStorageDataSource.writes shouldBe
                listOf(
                    RecordingMarkdownWrite(
                        directory = MemoDirectoryType.MAIN,
                        filename = "note.md",
                        content = merged,
                        append = false,
                    ),
                )
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md") shouldBe merged
            metadataDao.require(path).remotePath shouldBe remotePath
            (stateHolder.state.value is S3SyncState.Success).shouldBeTrue()
        }

    private fun `resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state`() =
        runTest {
            val path = "lomo/memo/too-large.md"
            val remotePath = "prefix/opaque-too-large"
            val localContent = numberedLines(prefix = "local")
            val remoteContent = numberedLines(prefix = "remote")
            val client = ConflictProbeS3Client()
            val markdownStorageDataSource = RecordingMarkdownStorageDataSource()
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            val pendingStore = InMemoryPendingSyncConflictStore()
            val baselineProtocolState =
                S3SyncProtocolState(
                    lastSuccessfulSyncAt = 10L,
                    lastFastSyncAt = 10L,
                    lastReconcileAt = 9L,
                    lastFullRemoteScanAt = 8L,
                    indexedLocalFileCount = 1,
                    indexedRemoteFileCount = 1,
                    localModeFingerprint = "legacy",
                    scanEpoch = 3L,
                )
            val protocolStateStore =
                InMemoryS3SyncProtocolStateStore().apply {
                    write(baselineProtocolState)
                }
            val baselineJournalEntry =
                S3LocalChangeJournalEntry(
                    id = "MEMO:too-large.md",
                    kind = S3LocalChangeKind.MEMO,
                    filename = "too-large.md",
                    changeType = S3LocalChangeType.UPSERT,
                    updatedAt = 50L,
                )
            val localChangeJournalStore =
                InMemoryS3LocalChangeJournalStore().apply {
                    upsert(baselineJournalEntry)
                }
            val baselineRemoteIndexEntry =
                S3RemoteIndexEntry(
                    relativePath = path,
                    remotePath = remotePath,
                    etag = "etag-1",
                    remoteLastModified = 10L,
                    size = 12L,
                    lastSeenAt = 10L,
                    lastVerifiedAt = 10L,
                    scanBucket = S3_SCAN_BUCKET_MEMO,
                    scanPriority = 100,
                    scanEpoch = 3L,
                )
            val remoteIndexStore =
                InMemoryS3RemoteIndexStore().apply {
                    upsert(listOf(baselineRemoteIndexEntry))
                }
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = metadataDao,
                    protocolStateStore = protocolStateStore,
                    localChangeJournalStore = localChangeJournalStore,
                    remoteIndexStore = remoteIndexStore,
                    pendingStore = pendingStore,
                    markdownStorageDataSource = markdownStorageDataSource,
                )
            val conflicts =
                conflictSet(
                    path = path,
                    localContent = localContent,
                    remoteContent = remoteContent,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.MERGE_TEXT),
                        ),
                    conflictSet = conflicts,
                )

            result shouldBe S3SyncResult.Conflict("Pending conflicts remain", conflicts)
            client.putKeys shouldBe emptyList()
            client.listCalls shouldBe 0
            protocolStateStore.read() shouldBe baselineProtocolState
            localChangeJournalStore.read() shouldBe mapOf(baselineJournalEntry.id to baselineJournalEntry)
            remoteIndexStore.readAll() shouldBe listOf(baselineRemoteIndexEntry)
            markdownStorageDataSource.writes shouldBe emptyList()
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "too-large.md") shouldBe null
            pendingStore.storedConflict(SyncBackendType.S3) shouldBe conflicts
            stateHolder.state.value shouldBe S3SyncState.ConflictDetected(conflicts)
            metadataDao.require(path).remotePath shouldBe remotePath
            coVerify(exactly = 0) { memoSynchronizer.refreshImportedSync() }
        }

    private fun `resolveConflicts rolls back metadata, remote index, and journal when final protocol commit fails`() =
        runTest {
            val path = "lomo/memo/note.md"
            val metadataDao = ConflictMetadataDao()
            val protocolStateStore =
                ConflictFailingWriteProtocolStateStore(
                    delegate =
                        InMemoryS3SyncProtocolStateStore().apply {
                            write(
                                S3SyncProtocolState(
                                    lastSuccessfulSyncAt = 10L,
                                    lastFullRemoteScanAt = 10L,
                                    indexedLocalFileCount = 0,
                                    indexedRemoteFileCount = 0,
                                ),
                            )
                        },
                    failure = IllegalStateException("protocol write failed"),
                )
            val localChangeJournalStore =
                InMemoryS3LocalChangeJournalStore().apply {
                    upsert(
                        S3LocalChangeJournalEntry(
                            id = "MEMO:note.md",
                            kind = S3LocalChangeKind.MEMO,
                            filename = "note.md",
                            changeType = S3LocalChangeType.UPSERT,
                            updatedAt = 50L,
                        ),
                    )
                }
            val remoteIndexStore = InMemoryS3RemoteIndexStore()
            val client = ConflictProbeS3Client()
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "note.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "note.md")
            } returns FileMetadata(filename = "note.md", lastModified = 50L)
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = metadataDao,
                    protocolStateStore = protocolStateStore,
                    localChangeJournalStore = localChangeJournalStore,
                    remoteIndexStore = remoteIndexStore,
                    transactionRunner =
                        rollbackableConflictResolutionTransactionRunner(
                            metadataDao = metadataDao,
                            protocolStateStore = protocolStateStore,
                            localChangeJournalStore = localChangeJournalStore,
                            remoteIndexStore = remoteIndexStore,
                        ),
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            (result is S3SyncResult.Error).shouldBeTrue()
            (metadataDao.getAll().isEmpty()).shouldBeTrue()
            (localChangeJournalStore.read().containsKey("MEMO:note.md")).shouldBeTrue()
            (remoteIndexStore.readAllRelativePaths().isEmpty()).shouldBeTrue()
        }

    private fun `resolveConflicts maps remote read failure without bucket scan`() =
        runTest {
            val path = "lomo/memo/note.md"
            val remotePath = "prefix/opaque-note"
            val client =
                ConflictProbeS3Client(
                    onGetObject = { key ->
                        key shouldBe remotePath
                        throw IllegalStateException("bucket failed")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao =
                        ConflictMetadataDao(
                            initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                        ),
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.BUCKET_ACCESS_FAILED
            error.message shouldBe "bucket failed"
            client.listCalls shouldBe 0
            (stateHolder.state.value is S3SyncState.Error).shouldBeTrue()
        }

    private fun `resolveConflicts keeps skipped files pending and returns conflict state`() =
        runTest {
            val keptPath = "lomo/memo/kept.md"
            val skippedPath = "lomo/memo/skipped.md"
            val remotePath = "prefix/opaque-kept"
            val client =
                ConflictProbeS3Client(
                    onPutObject = { key, _ ->
                        key shouldBe remotePath
                        S3PutObjectResult(eTag = "etag-uploaded")
                    },
                )
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = keptPath, remotePath = remotePath)),
                )
            val pendingStore = InMemoryPendingSyncConflictStore()
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "kept.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "kept.md")
            } returns FileMetadata(filename = "kept.md", lastModified = 50L)
            val resolver = createResolver(client = client, metadataDao = metadataDao, pendingStore = pendingStore)
            val conflictSet =
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = keptPath,
                                localContent = "# local",
                                remoteContent = "# remote",
                                isBinary = false,
                            ),
                            SyncConflictFile(
                                relativePath = skippedPath,
                                localContent = "left",
                                remoteContent = "right",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 1L,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices =
                                mapOf(
                                    keptPath to SyncConflictResolutionChoice.KEEP_LOCAL,
                                    skippedPath to SyncConflictResolutionChoice.SKIP_FOR_NOW,
                                ),
                        ),
                    conflictSet = conflictSet,
                )

            result shouldBe S3SyncResult.Conflict(
                    message = "Pending conflicts remain",
                    conflicts =
                        conflictSet.copy(
                            files = listOf(conflictSet.files[1]),
                        ),
                )
            client.putKeys shouldBe listOf(remotePath)
            pendingStore.storedConflict(SyncBackendType.S3) shouldBe conflictSet.copy(files = listOf(conflictSet.files[1]))
            stateHolder.state.value shouldBe S3SyncState.ConflictDetected(conflictSet.copy(files = listOf(conflictSet.files[1])))
        }

    private fun `resolveReview invalidates pending descriptor before applying choices`() =
        runTest {
            val path = "lomo/memo/review.md"
            val remotePath = "prefix/opaque-review"
            val review =
                SyncReviewSession(
                    source = SyncBackendType.S3,
                    items =
                        listOf(
                            SyncReviewItem(
                                relativePath = path,
                                localContent = "# local",
                                incomingContent = "# incoming",
                                isBinary = false,
                                localLastModified = 10L,
                                incomingLastModified = 20L,
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = 42L,
                    kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                )
            val pendingStore = S3TrackingPendingSyncReviewStore()
            pendingStore.descriptor =
                PendingSyncReviewDescriptor(
                    source = SyncBackendType.S3,
                    workspaceGeneration = "test",
                    kind = review.kind,
                    items =
                        listOf(
                            PendingSyncReviewItemDescriptor(
                                relativePath = path,
                                isBinary = false,
                                local =
                                    PendingSyncSideMetadata(
                                        locator = path,
                                        contentHash = "local-hash",
                                        lastModified = 10L,
                                        size = 7L,
                                        etag = "local-etag",
                                    ),
                                incoming =
                                    PendingSyncSideMetadata(
                                        locator = remotePath,
                                        contentHash = "incoming-hash",
                                        lastModified = 20L,
                                        size = 10L,
                                        etag = null,
                                    ),
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = 42L,
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
                )
            val client = ConflictProbeS3Client()
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = remotePath)),
                )
            val resolver = createReviewResolver(client = client, metadataDao = metadataDao, pendingStore = pendingStore)
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, review)

            result shouldBe S3SyncResult.Error("Pending S3 review session requires rebuild: STALE_LOCAL")
            pendingStore.clearCalls shouldBe listOf(SyncBackendType.S3)
            client.getObjectKeys shouldBe emptyList()
            client.putKeys shouldBe emptyList()
        }

    private fun `resolveReview meters pending descriptor remote validation`() =
        runTest {
            val path = "lomo/images/review.bin"
            val review =
                SyncReviewSession(
                    source = SyncBackendType.S3,
                    items =
                        listOf(
                            SyncReviewItem(
                                relativePath = path,
                                localContent = null,
                                incomingContent = null,
                                isBinary = true,
                                localLastModified = 10L,
                                incomingLastModified = 20L,
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = 42L,
                    kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                )
            val pendingStore = S3TrackingPendingSyncReviewStore()
            pendingStore.descriptor =
                PendingSyncReviewDescriptor(
                    source = SyncBackendType.S3,
                    workspaceGeneration = "test",
                    kind = review.kind,
                    items =
                        listOf(
                            PendingSyncReviewItemDescriptor(
                                relativePath = path,
                                isBinary = false,
                                local =
                                    PendingSyncSideMetadata(
                                        locator = path,
                                        contentHash = null,
                                        lastModified = 10L,
                                        size = 4L,
                                        etag = null,
                                    ),
                                incoming =
                                    PendingSyncSideMetadata(
                                        locator = "prefix/opaque-review",
                                        contentHash = null,
                                        lastModified = 20L,
                                        size = 10L,
                                        etag = "etag-live",
                                    ),
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = 42L,
                    validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
                )
            val owner = TestSyncLifecycleExecutionOwner()
            coEvery { localMediaSyncStore.getFile(path, any()) } returns
                LocalMediaSyncFile(relativePath = path, lastModified = 10L, size = 4L)
            val resolver =
                createReviewResolver(
                    client = ConflictProbeS3Client(),
                    metadataDao = ConflictMetadataDao(initial = listOf(stableMetadata(path = path, remotePath = "prefix/opaque-review"))),
                    pendingStore = pendingStore,
                    lifecycleRunner = DefaultRemoteSyncLifecycleRunner(owner),
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, review)

            result shouldBe S3SyncResult.Error("Pending S3 review session requires rebuild: MISSING_REMOTE")
            val report = owner.reports.single()
            report.network.head shouldBe 1
            report.budget.consumedNetworkOperations shouldBe 1
        }

    private fun createResolver(
        client: ConflictProbeS3Client,
        metadataDao: ConflictMetadataDao,
        protocolStateStore: S3SyncProtocolStateStore = InMemoryS3SyncProtocolStateStore(),
        localChangeJournalStore: S3LocalChangeJournalStore = InMemoryS3LocalChangeJournalStore(),
        remoteIndexStore: S3RemoteIndexStore = DisabledS3RemoteIndexStore,
        pendingStore: PendingSyncConflictStore = InMemoryPendingSyncConflictStore(),
        transactionRunner: S3SyncTransactionRunner = NoOpS3SyncTransactionRunner,
        markdownStorageDataSource: MarkdownStorageDataSource = this.markdownStorageDataSource,
    ): S3ConflictResolver {
        every { clientFactory.create(any()) } returns client
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(timestampToleranceMs = 0L),
                stateHolder = stateHolder,
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = transactionRunner,
            )
        val encodingSupport = S3SyncEncodingSupport()
        fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        support = S3SyncRepositorySupport(runtime)
        return S3ConflictResolver(
            runtime = runtime,
            support = support,
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = localChangeJournalStore,
            remoteIndexStore = remoteIndexStore,
            pendingConflictStore = pendingStore,
            transferWorkspace = S3SyncTransferWorkspace.systemTemp(),
            lifecycleRunner = testRemoteSyncLifecycleRunner(),
        )
    }

    private fun createReviewResolver(
        client: ConflictProbeS3Client,
        metadataDao: ConflictMetadataDao,
        protocolStateStore: S3SyncProtocolStateStore = InMemoryS3SyncProtocolStateStore(),
        localChangeJournalStore: S3LocalChangeJournalStore = InMemoryS3LocalChangeJournalStore(),
        remoteIndexStore: S3RemoteIndexStore = DisabledS3RemoteIndexStore,
        pendingStore: PendingSyncReviewStore = InMemoryPendingSyncReviewStore(),
        transactionRunner: S3SyncTransactionRunner = NoOpS3SyncTransactionRunner,
        markdownStorageDataSource: MarkdownStorageDataSource = this.markdownStorageDataSource,
        lifecycleRunner: RemoteSyncLifecycleRunner = testRemoteSyncLifecycleRunner(),
    ): S3ReviewResolver {
        every { clientFactory.create(any()) } returns client
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(timestampToleranceMs = 0L),
                stateHolder = stateHolder,
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = transactionRunner,
            )
        val encodingSupport = S3SyncEncodingSupport()
        val fileBridge = S3SyncFileBridge(runtime, encodingSupport)
        val support = S3SyncRepositorySupport(runtime)
        return S3ReviewResolver(
            runtime = runtime,
            support = support,
            encodingSupport = encodingSupport,
            fileBridge = fileBridge,
            protocolStateStore = protocolStateStore,
            localChangeJournalStore = localChangeJournalStore,
            remoteIndexStore = remoteIndexStore,
            pendingReviewStore = pendingStore,
            transferWorkspace = S3SyncTransferWorkspace.systemTemp(),
            lifecycleRunner = lifecycleRunner,
        )
    }

    private fun stableMetadata(
        path: String,
        remotePath: String,
    ) = S3SyncMetadataEntity(
        relativePath = path,
        remotePath = remotePath,
        etag = "etag-1",
        remoteLastModified = 10L,
        localLastModified = 10L,
        lastSyncedAt = 10L,
        lastResolvedDirection = S3SyncMetadataEntity.NONE,
        lastResolvedReason = S3SyncMetadataEntity.UNCHANGED,
    )

    private fun conflictSet(
        path: String,
        localContent: String = "# local",
        remoteContent: String = "# remote",
    ): SyncConflictSet =
        SyncConflictSet(
            source = SyncBackendType.S3,
            files =
                listOf(
                    SyncConflictFile(
                        relativePath = path,
                        localContent = localContent,
                        remoteContent = remoteContent,
                        isBinary = false,
                    ),
                ),
            timestamp = 1L,
        )

    private fun numberedLines(prefix: String): String =
        (0..1_000).joinToString("\n") { index -> "$prefix-$index" }
}

private class ConflictMetadataDao(
    initial: List<S3SyncMetadataEntity> = emptyList(),
) : S3SyncMetadataDao {
    private val entries = linkedMapOf<String, S3SyncMetadataEntity>()

    init {
        initial.forEach { entity -> entries[entity.relativePath] = entity }
    }

    override suspend fun getAll(): List<S3SyncMetadataEntity> = entries.values.toList()

    override suspend fun getAllPlannerMetadataSnapshots(): List<S3SyncPlannerMetadataSnapshot> =
        entries.values.map { entity ->
            S3SyncPlannerMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
                localLastModified = entity.localLastModified,
                lastSyncedAt = entity.lastSyncedAt,
                lastResolvedDirection = entity.lastResolvedDirection,
                lastResolvedReason = entity.lastResolvedReason,
            )
        }

    override suspend fun getAllRemoteMetadataSnapshots(): List<S3SyncRemoteMetadataSnapshot> =
        entries.values.map { entity ->
            S3SyncRemoteMetadataSnapshot(
                relativePath = entity.relativePath,
                remotePath = entity.remotePath,
                etag = entity.etag,
                remoteLastModified = entity.remoteLastModified,
            )
        }

    override suspend fun getByRelativePaths(relativePaths: List<String>): List<S3SyncMetadataEntity> =
        relativePaths.mapNotNull(entries::get)

    override suspend fun upsertAll(entities: List<S3SyncMetadataEntity>) {
        entities.forEach { entity ->
            entries[entity.relativePath] = entity
        }
    }

    override suspend fun deleteByRelativePath(relativePath: String) {
        entries.remove(relativePath)
    }

    override suspend fun deleteByRelativePaths(relativePaths: List<String>) {
        relativePaths.forEach(entries::remove)
    }

    override suspend fun clearAll() {
        entries.clear()
    }

    fun require(path: String): S3SyncMetadataEntity = requireNotNull(entries[path])

    suspend fun snapshot(): List<S3SyncMetadataEntity> = getAll()

    suspend fun restore(snapshot: List<S3SyncMetadataEntity>) {
        clearAll()
        upsertAll(snapshot)
    }
}

private class ConflictProbeS3Client(
    private val onGetObject: suspend (String) -> S3SmallObjectPayload = {
        throw AssertionError("Unexpected getObject for $it")
    },
    private val onPutObject: suspend (String, ByteArray) -> S3PutObjectResult = { _, _ ->
        S3PutObjectResult(eTag = "etag-uploaded")
    },
) : LomoS3Client {
    var listCalls: Int = 0
    val getObjectKeys = mutableListOf<String>()
    val putKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? = null

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> {
        listCalls += 1
        throw AssertionError("Conflict resolution should not list remote objects")
    }

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        getObjectKeys += key
        return onGetObject(key)
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
    ): S3PutObjectResult {
        putKeys += key
        return onPutObject(key, bytes)
    }

    override suspend fun getObjectToFile(
        key: String,
        destination: java.io.File,
    ): com.lomo.data.s3.S3RemoteObject {
        val payload = getSmallObject(key)
        destination.parentFile?.mkdirs()
        destination.writeBytes(payload.bytes)
        return com.lomo.data.s3.S3RemoteObject(
            key = payload.key,
            eTag = payload.eTag,
            lastModified = payload.lastModified,
            size = destination.length(),
            metadata = payload.metadata,
        )
    }

    override suspend fun putObjectFile(
        key: String,
        file: java.io.File,
        contentType: String,
        metadata: Map<String, String>,
    ): com.lomo.data.s3.S3PutObjectResult =
        putSmallObject(key, file.readBytes(), contentType, metadata)

    override suspend fun deleteObject(key: String) =
        throw AssertionError("Conflict resolution should not delete remote objects")

    override fun close() = Unit
}

private class S3TrackingPendingSyncReviewStore : PendingSyncReviewStore {
    var descriptor: PendingSyncReviewDescriptor? = null
    val clearCalls = mutableListOf<SyncBackendType>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? = descriptor

    override suspend fun write(review: SyncReviewSession) = Unit

    override suspend fun clear(source: SyncBackendType) {
        clearCalls += source
    }
}

private data class RecordingMarkdownWrite(
    val directory: MemoDirectoryType,
    val filename: String,
    val content: String,
    val append: Boolean,
)

private class RecordingMarkdownStorageDataSource(
    private val metadataLastModified: Long = 1L,
) : MarkdownStorageDataSource {
    private val files: Map<MemoDirectoryType, LinkedHashMap<String, String>> =
        MemoDirectoryType.entries.associateWith { linkedMapOf() }
    val writes = mutableListOf<RecordingMarkdownWrite>()

    override suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata> =
        files.getValue(directory).map { (filename, content) ->
            FileMetadata(
                filename = filename,
                lastModified = metadataLastModified,
                size = content.length.toLong(),
            )
        }

    override suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId> =
        files.getValue(directory).keys.map { filename ->
            FileMetadataWithId(
                filename = filename,
                lastModified = metadataLastModified,
                documentId = filename,
            )
        }

    override fun streamMetadataWithIdsIn(directory: MemoDirectoryType): kotlinx.coroutines.flow.Flow<FileMetadataWithId> =
        flow {
            listMetadataWithIdsIn(directory).forEach { metadata -> emit(metadata) }
        }

    override suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String? = files.getValue(directory)[documentId]

    override suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? = files.getValue(directory)[filename]

    override suspend fun readFile(uri: android.net.Uri): String? =
        error("Unexpected URI read in S3 conflict resolver test")

    override suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean,
        uri: android.net.Uri?,
    ): String? {
        writes +=
            RecordingMarkdownWrite(
                directory = directory,
                filename = filename,
                content = content,
                append = append,
            )
        files.getValue(directory)[filename] =
            if (append) {
                files.getValue(directory)[filename].orEmpty() + content
            } else {
                content
            }
        return null
    }

    override suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: android.net.Uri?,
    ) {
        files.getValue(directory).remove(filename)
    }

    override suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata? =
        files.getValue(directory)[filename]?.let { content ->
            FileMetadata(
                filename = filename,
                lastModified = metadataLastModified,
                size = content.length.toLong(),
            )
        }
}

private class ConflictFailingWriteProtocolStateStore(
    private val delegate: S3SyncProtocolStateStore,
    private val failure: Throwable,
) : S3SyncProtocolStateStore by delegate {
    private var failWrites: Boolean = true

    override suspend fun write(state: S3SyncProtocolState) {
        if (failWrites) {
            throw failure
        }
        delegate.write(state)
    }

    suspend fun restoreSnapshot(state: S3SyncProtocolState?) {
        failWrites = false
        delegate.clear()
        if (state != null) {
            delegate.write(state)
        }
        failWrites = true
    }
}

private fun rollbackableConflictResolutionTransactionRunner(
    metadataDao: ConflictMetadataDao,
    protocolStateStore: S3SyncProtocolStateStore,
    localChangeJournalStore: S3LocalChangeJournalStore,
    remoteIndexStore: S3RemoteIndexStore,
): S3SyncTransactionRunner =
    object : S3SyncTransactionRunner {
        override suspend fun <T> runInTransaction(block: suspend () -> T): T {
            val metadataSnapshot = metadataDao.snapshot()
            val protocolSnapshot = protocolStateStore.read()
            val journalSnapshot = localChangeJournalStore.read().values.toList()
            val remoteIndexSnapshot =
                remoteIndexStore.readByRelativePaths(remoteIndexStore.readAllRelativePaths())
            return try {
                block()
            } catch (error: Throwable) {
                metadataDao.restore(metadataSnapshot)
                if (protocolStateStore is ConflictFailingWriteProtocolStateStore) {
                    protocolStateStore.restoreSnapshot(protocolSnapshot)
                } else {
                    protocolStateStore.clear()
                    if (protocolSnapshot != null) {
                        protocolStateStore.write(protocolSnapshot)
                    }
                }
                localChangeJournalStore.clear()
                journalSnapshot.forEach { entry ->
                    localChangeJournalStore.upsert(entry)
                }
                remoteIndexStore.clear()
                remoteIndexStore.upsert(remoteIndexSnapshot)
                throw error
            }
        }
    }
