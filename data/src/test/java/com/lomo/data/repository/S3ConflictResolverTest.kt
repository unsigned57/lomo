package com.lomo.data.repository

import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.dao.S3SyncPlannerMetadataSnapshot
import com.lomo.data.local.dao.S3SyncRemoteMetadataSnapshot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.data.local.entity.PendingSyncReviewEntity
import com.lomo.data.local.entity.S3SyncMetadataEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.FileMetadataWithId
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.testing.KotestTemporaryFolder
import com.lomo.data.webdav.LocalMediaSyncFile
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncErrorCode
import com.lomo.domain.model.S3SyncReason
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
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: S3ConflictResolver
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: apply explicit S3 conflict/review choices without whole-bucket scans while preserving exact S3 object locators at pending restore and final client boundaries.
 *
 * Scenarios:
 * - Given indexed metadata, when KEEP_LOCAL or KEEP_REMOTE is chosen, then the resolver uses the indexed object path without remote listing.
 * - Given production S3 materialization persists a pending conflict locator that equals relativePath through Room, when resolution restores and applies the session, then that exact S3 locator is used for HEAD/GET and apply.
 * - Given production S3 materialization persists a binary/non-memo pending conflict through Room, when
 *   unchanged side metadata is validated, then resolution succeeds without relying on text content.
 * - Given binary/non-memo pending side metadata changes, when resolution starts, then validation
 *   rejects before any S3 write.
 * - Given binary/non-memo pending remote side metadata is incomplete, when resolution starts, then
 *   validation rejects before any S3 client call.
 * - Given a memo (text) pending conflict whose local and remote content are unchanged but whose remote
 *   lastModified normalizes differently (cached raw HTTP time vs fresh app-embedded mtime), when the
 *   user applies a resolution, then the conflict resolves on the first pass instead of false-failing
 *   STALE_REMOTE and forcing a second apply.
 * - Given a memo (text) pending review whose local and incoming content are unchanged but whose incoming
 *   lastModified normalizes differently, when review resolution runs, then the item is restored and
 *   resolved on the first pass instead of false-failing STALE_REMOTE.
 * - Given production S3 materialization persists a pending review locator that equals relativePath through Room, when resolution restores and applies the session, then that exact S3 locator is used for HEAD/GET and apply.
 * - Given production S3 materialization persists a binary/non-memo pending review through Room, when
 *   incoming side metadata is unchanged, then review resolution succeeds without relying on text content.
 * - Given production-persisted pending descriptors contain an outside-prefix locator, when restore/resolve runs, then the key is rejected before any S3 client call.
 * - Given MERGE_TEXT can merge memo content, when resolution runs, then the merged memo is written locally and uploaded once.
 * - Given MERGE_TEXT exceeds the text-merge budget, when resolution runs, then the conflict remains pending and no local write or upload occurs.
 * - Given incremental S3 stores already contain protocol, journal, and remote-index state, when every MERGE_TEXT conflict is declined by the merge budget, then only pending conflict/UI state changes.
 * - Given SKIP_FOR_NOW is chosen for a file, when other files are resolved, then skipped files remain pending for review.
 * - Given remote IO fails, when resolution runs, then the failure is mapped to an S3 error result.
 *
 * Observable outcomes:
 * - S3SyncResult subtype, pending conflict/review store contents, state-holder value, remote head/get/put targets, protocol state, journal state, remote index state, metadata persistence, local content, and memo refresh side effects.
 *
 * TDD proof:
 * - RED: the MERGE_TEXT budget scenario returned S3SyncResult.Error("Unable to merge conflict...") instead of preserving a pending conflict.
 * - RED follow-up-2 command: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3ConflictResolverTest'
 * - RED follow-up-2 symptom: resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state failed because protocolStateStore.read() differed from the baseline; lastSuccessfulSyncAt/lastFastSyncAt were updated even though no file resolved.
 * - RED Report 10 follow-up: pending review restored locator == relativePath as prefix/prefix/... during apply, stale pending locators mapped to UNKNOWN after local validation, and conflict restore lacked exact-locator coverage.
 * - RED Report 10 proof-depth follow-up: resolver locator preservation and stale rejection tests manually seeded final descriptors instead of chaining production S3 materialization plus Room pending-store persistence.
 * - RED memo-mtime follow-up command: ./gradlew :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3ConflictResolverTest'
 * - RED memo-mtime symptom: "resolveConflicts resolves memo conflict on first pass when only remote mtime normalization differs" expected Success(Conflicts resolved) but was Error(message=Pending S3 conflict session requires rebuild: STALE_REMOTE) because validation compared the descriptor's raw captured lastModified against a freshly resolved app-embedded mtime.
 * - GREEN follow-up-2 worker reported: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.S3ConflictResolverTest' --tests 'com.lomo.data.git.GitSyncEngineConflictTest' -> BUILD SUCCESSFUL in 44s.
 * - GREEN WebDav regression worker reported: ./gradlew --no-daemon --no-configuration-cache --console=plain :data:testDebugUnitTest --tests 'com.lomo.data.repository.WebDavConflictResolverTest' -> BUILD SUCCESSFUL in 18s.
 *
 * Excludes:
 * - AWS SDK transport details, planner internals, metadata persistence internals, and UI rendering.
 */

/*
 * Test Change Justification:
 * - Reason category: Signature update
 * - Old behavior/assertion being replaced: Fake S3 client collaborator putObjectFile and putSmallObject signatures without ifMatch and ifNoneMatch parameters.
 * - Why old assertion is no longer correct: The production S3 client interface has been upgraded with conditional write parameters for performance optimization.
 * - Coverage preserved by: All original test assertions are unchanged; the fake client is updated to compile against the new interface signature.
 * - Why this is not fitting the test to the implementation: This is a mechanical signature update to satisfy compile safety, not a change to the tested behavior.
 */
class S3ConflictResolverTest : DataFunSpec() {
    init {
        beforeTest {
            setUp()
        }

        afterTest {
            tempFolder?.cleanup()
            tempFolder = null
        }

        test("resolveConflicts keeps remote using indexed remote path without remote listing") { `resolveConflicts keeps remote using indexed remote path without remote listing`() }

        test("resolveConflicts keeps remote using remote index path when metadata snapshot is missing") { `resolveConflicts keeps remote using remote index path when metadata snapshot is missing`() }

        test("resolveConflicts keeps local using indexed remote path without remote listing") { `resolveConflicts keeps local using indexed remote path without remote listing`() }

        test("resolveConflicts rejects stale metadata key outside configured prefix before upload") {
            `resolveConflicts rejects stale metadata key outside configured prefix before upload`()
        }

        test("resolveConflicts preserves production-persisted opaque pending locator") {
            `resolveConflicts preserves production-persisted opaque pending locator`()
        }

        test("resolveConflicts resolves memo conflict on first pass when only remote mtime normalization differs") {
            `resolveConflicts resolves memo conflict on first pass when only remote mtime normalization differs`()
        }

        test("resolveConflicts resolves binary pending conflict when side metadata matches") {
            `resolveConflicts resolves binary pending conflict when side metadata matches`()
        }

        test("resolveConflicts invalidates binary pending conflict when local fingerprint changes before upload") {
            `resolveConflicts invalidates binary pending conflict when local fingerprint changes before upload`()
        }

        test("resolveConflicts invalidates binary pending conflict when remote etag changes before upload") {
            `resolveConflicts invalidates binary pending conflict when remote etag changes before upload`()
        }

        test("resolveConflicts rejects incomplete binary remote descriptor before head or upload") {
            `resolveConflicts rejects incomplete binary remote descriptor before head or upload`()
        }

        test("resolveConflicts MERGE_TEXT writes merged memo locally and uploads it without remote listing") { `resolveConflicts MERGE_TEXT writes merged memo locally and uploads it without remote listing`() }

        test("resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state") { `resolveConflicts MERGE_TEXT keeps over-budget memo conflict pending without writing protocol state`() }

        test("resolveConflicts rolls back metadata, remote index, and journal when final protocol commit fails") { `resolveConflicts rolls back metadata, remote index, and journal when final protocol commit fails`() }

        test("resolveConflicts maps remote read failure without bucket scan") { `resolveConflicts maps remote read failure without bucket scan`() }

        test("resolveConflicts keeps skipped files pending and returns conflict state") { `resolveConflicts keeps skipped files pending and returns conflict state`() }

        test("resolveConflicts preserves pending locator when it equals relative path") {
            `resolveConflicts preserves pending locator when it equals relative path`()
        }

        test("resolveConflicts rejects stale pending locator before head or get") {
            `resolveConflicts rejects stale pending locator before head or get`()
        }

        test("resolveReview invalidates pending descriptor before applying choices") {
            `resolveReview invalidates pending descriptor before applying choices`()
        }

        test("resolveReview meters pending descriptor remote validation") {
            `resolveReview meters pending descriptor remote validation`()
        }

        test("resolveReview rejects stale metadata key outside configured prefix before download") {
            `resolveReview rejects stale metadata key outside configured prefix before download`()
        }

        test("resolveReview preserves pending locator when it equals relative path") {
            `resolveReview preserves pending locator when it equals relative path`()
        }

        test("resolveReview resolves memo review on first pass when only remote mtime normalization differs") {
            `resolveReview resolves memo review on first pass when only remote mtime normalization differs`()
        }

        test("resolveReview resolves binary incoming side when side metadata matches") {
            `resolveReview resolves binary incoming side when side metadata matches`()
        }

        test("resolveReview rejects incomplete binary incoming descriptor before head or download") {
            `resolveReview rejects incomplete binary incoming descriptor before head or download`()
        }

        test("resolveReview rejects stale pending locator before head or get") {
            `resolveReview rejects stale pending locator before head or get`()
        }

        test("resolveReview rejects stale metadata key outside configured prefix before upload") {
            `resolveReview rejects stale metadata key outside configured prefix before upload`()
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
    private var tempFolder: KotestTemporaryFolder? = null
    private val materializationLayout =
        SyncDirectoryLayout(
            memoFolder = "memo",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    private fun setUp() {
        tempFolder?.cleanup()
        tempFolder = null
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
        every { credentialStore.getSecret(CredentialField.S3_ACCESS_KEY_ID) } returns "access"
        every { credentialStore.getSecret(CredentialField.S3_SECRET_ACCESS_KEY) } returns "secret"
        every { credentialStore.getSecret(CredentialField.S3_SESSION_TOKEN) } returns null
        every { credentialStore.getSecret(CredentialField.S3_ENCRYPTION_PASSWORD) } returns null
        every { credentialStore.getSecret(CredentialField.S3_ENCRYPTION_PASSWORD2) } returns null
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

    private fun `resolveConflicts rejects stale metadata key outside configured prefix before upload`() =
        runTest {
            val path = "lomo/memo/stale.md"
            val staleRemotePath = "old-prefix/opaque-stale"
            val client = ConflictProbeS3Client()
            val metadataDao =
                ConflictMetadataDao(
                    initial = listOf(stableMetadata(path = path, remotePath = staleRemotePath)),
                )
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "stale.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "stale.md")
            } returns FileMetadata(filename = "stale.md", lastModified = 50L)
            val resolver = createResolver(client = client, metadataDao = metadataDao)

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = conflictSet(path = path),
                )

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION
            error.message shouldBe "S3 remote layout key is outside the configured prefix"
            client.putKeys shouldBe emptyList()
            client.listCalls shouldBe 0
            (stateHolder.state.value is S3SyncState.Error).shouldBeTrue()
        }

    private fun `resolveConflicts preserves production-persisted opaque pending locator`() =
        runTest {
            val path = "notes/opaque-conflict.md"
            val remotePath = "prefix/rclone/opaque-conflict-key"
            val localContent = "# local"
            val remoteContent = "# remote"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = localContent,
                    remoteContent = remoteContent,
                    rootName = "s3-materialized-opaque-conflict",
                )
            pipeline.conflictStore.readDescriptor(SyncBackendType.S3)?.files?.single()?.remote?.locator shouldBe
                remotePath
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = remoteContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.headKeys shouldBe listOf(remotePath)
            client.getObjectKeys shouldBe listOf(remotePath, remotePath)
            client.putKeys shouldBe emptyList()
        }

    private fun `resolveConflicts resolves memo conflict on first pass when only remote mtime normalization differs`() =
        runTest {
            val path = "notes/memo-mtime-conflict.md"
            val remotePath = "prefix/rclone/memo-mtime-key"
            val localContent = "# local"
            val remoteContent = "# remote"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = localContent,
                    remoteContent = remoteContent,
                    rootName = "s3-memo-mtime-conflict",
                )
            // Production materialization captured the discovered (raw) remote lastModified into the pending
            // descriptor, exactly as the cached remote index records it.
            pipeline.conflictStore.readDescriptor(SyncBackendType.S3)?.files?.single()?.remote?.lastModified shouldBe 20L
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        // Same object: etag, size, and content are unchanged. Only the app-embedded mtime
                        // metadata resolves to a different millisecond value (99 s -> 99_000 ms) than the
                        // raw lastModified captured at detection.
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = mapOf("mtime" to "99"),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = mapOf("mtime" to "99"),
                            bytes = remoteContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.putKeys shouldBe emptyList()
            (stateHolder.state.value is S3SyncState.Success).shouldBeTrue()
        }

    private fun `resolveConflicts resolves binary pending conflict when side metadata matches`() =
        runTest {
            val path = "assets/photo.png"
            val remotePath = "prefix/rclone/photo-opaque"
            val localContent = "local-binary"
            val remoteContent = "remote-binary"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = localContent,
                    remoteContent = remoteContent,
                    rootName = "s3-binary-conflict-resolve",
                )
            val descriptor = requireNotNull(pipeline.conflictStore.readDescriptor(SyncBackendType.S3))
            descriptor.files.single().local.contentHash shouldBe localContent.toByteArray(Charsets.UTF_8).md5Hex()
            pipeline.conflictSet.files.single().localContent shouldBe null
            pipeline.conflictSet.files.single().remoteContent shouldBe null
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onPutObject = { key, bytes ->
                        key shouldBe remotePath
                        bytes.toString(Charsets.UTF_8) shouldBe localContent
                        S3PutObjectResult(eTag = "etag-uploaded")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.headKeys shouldBe listOf(remotePath)
            client.getObjectKeys shouldBe emptyList()
            client.putKeys shouldBe listOf(remotePath)
            pipeline.conflictStore.readDescriptor(SyncBackendType.S3) shouldBe null
        }

    private fun `resolveConflicts invalidates binary pending conflict when local fingerprint changes before upload`() =
        runTest {
            val path = "assets/photo.png"
            val remotePath = "prefix/rclone/photo-opaque"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = "abcd",
                    remoteContent = "remote-binary",
                    rootName = "s3-binary-conflict-stale-local",
                )
            pipeline.root.resolve(path).writeText("wxyz", Charsets.UTF_8)
            pipeline.root.resolve(path).setLastModified(10L)
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        throw AssertionError("stale local fingerprint must reject before HEAD: $key")
                    },
                    onPutObject = { key, _ ->
                        throw AssertionError("stale local fingerprint must reject before upload: $key")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Error("Pending S3 conflict session requires rebuild: STALE_LOCAL")
            client.headKeys shouldBe emptyList()
            client.getObjectKeys shouldBe emptyList()
            client.putKeys shouldBe emptyList()
        }

    private fun `resolveConflicts invalidates binary pending conflict when remote etag changes before upload`() =
        runTest {
            val path = "assets/photo.png"
            val remotePath = "prefix/rclone/photo-opaque"
            val remoteContent = "remote-binary"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = "local-binary",
                    remoteContent = remoteContent,
                    rootName = "s3-binary-conflict-stale-remote",
                )
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-changed",
                            lastModified = 20L,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onPutObject = { key, _ ->
                        throw AssertionError("stale remote metadata must reject before upload: $key")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Error("Pending S3 conflict session requires rebuild: STALE_REMOTE")
            client.headKeys shouldBe listOf(remotePath)
            client.getObjectKeys shouldBe emptyList()
            client.putKeys shouldBe emptyList()
        }

    private fun `resolveConflicts rejects incomplete binary remote descriptor before head or upload`() =
        runTest {
            val path = "assets/photo.png"
            val remotePath = "prefix/rclone/photo-opaque"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = "local-binary",
                    remoteContent = "remote-binary",
                    rootName = "s3-binary-conflict-incomplete-remote",
                )
            val descriptor = requireNotNull(pipeline.conflictStore.readDescriptor(SyncBackendType.S3))
            pipeline.conflictStore.writeDescriptor(
                descriptor.copy(
                    files =
                        descriptor.files.map { file ->
                            file.copy(remote = file.remote.copy(etag = null))
                        },
                ),
            )
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        throw AssertionError("incomplete remote metadata must reject before HEAD: $key")
                    },
                    onPutObject = { key, _ ->
                        throw AssertionError("incomplete remote metadata must reject before upload: $key")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_LOCAL),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Error("Pending S3 conflict session requires rebuild: STALE_REMOTE")
            client.headKeys shouldBe emptyList<String>()
            client.getObjectKeys shouldBe emptyList<String>()
            client.putKeys shouldBe emptyList()
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
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-1",
                            lastModified = 10L,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-1",
                            lastModified = 10L,
                            metadata = emptyMap(),
                            bytes = remoteContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
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
            markdownStorageDataSource.saveFileIn(
                directory = MemoDirectoryType.MAIN,
                filename = "too-large.md",
                content = localContent,
                append = false,
                uri = null,
            )
            markdownStorageDataSource.writes.clear()
            pendingStore.writeDescriptor(
                conflicts.toPendingDescriptor(
                    remoteLocators = mapOf(path to remotePath),
                    localLastModified = 1L,
                    remoteLastModified = 10L,
                    remoteEtags = mapOf(path to "etag-1"),
                ),
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
            markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "too-large.md") shouldBe localContent
            pendingStore.readDescriptor(SyncBackendType.S3)?.files?.single()?.remote?.locator shouldBe remotePath
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
            error.message shouldBe "S3 bucket access failed. Check the bucket name, region, endpoint, and permissions."
            client.listCalls shouldBe 0
            (stateHolder.state.value is S3SyncState.Error).shouldBeTrue()
        }

    private fun `resolveConflicts keeps skipped files pending and returns conflict state`() =
        runTest {
            val keptPath = "lomo/memo/kept.md"
            val skippedPath = "lomo/memo/skipped.md"
            val remotePath = "prefix/opaque-kept"
            val skippedRemotePath = "prefix/opaque-skipped"
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        when (key) {
                            remotePath ->
                                S3RemoteObject(
                                    key = key,
                                    eTag = "etag-kept",
                                    lastModified = 20L,
                                    size = "# remote".toByteArray(Charsets.UTF_8).size.toLong(),
                                    metadata = emptyMap(),
                                )
                            skippedRemotePath ->
                                S3RemoteObject(
                                    key = key,
                                    eTag = "etag-skipped",
                                    lastModified = 30L,
                                    size = "right".toByteArray(Charsets.UTF_8).size.toLong(),
                                    metadata = emptyMap(),
                                )
                            else -> error("Unexpected HEAD key $key")
                        }
                    },
                    onGetObject = { key ->
                        when (key) {
                            remotePath ->
                                S3SmallObjectPayload(
                                    key = key,
                                    eTag = "etag-kept",
                                    lastModified = 20L,
                                    metadata = emptyMap(),
                                    bytes = "# remote".toByteArray(Charsets.UTF_8),
                                )
                            skippedRemotePath ->
                                S3SmallObjectPayload(
                                    key = key,
                                    eTag = "etag-skipped",
                                    lastModified = 30L,
                                    metadata = emptyMap(),
                                    bytes = "right".toByteArray(Charsets.UTF_8),
                                )
                            else -> error("Unexpected GET key $key")
                        }
                    },
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
            } returns FileMetadata(filename = "kept.md", lastModified = 50L, size = "# local".toByteArray(Charsets.UTF_8).size.toLong())
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "skipped.md")
            } returns "left"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "skipped.md")
            } returns FileMetadata(filename = "skipped.md", lastModified = 60L, size = "left".toByteArray(Charsets.UTF_8).size.toLong())
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
            pendingStore.writeDescriptor(
                conflictSet.toPendingDescriptor(
                    remoteLocators = mapOf(keptPath to remotePath, skippedPath to skippedRemotePath),
                    localLastModified = 50L,
                    remoteLastModified = 20L,
                    localLastModifiedByPath = mapOf(skippedPath to 60L),
                    remoteLastModifiedByPath = mapOf(skippedPath to 30L),
                    remoteEtags = mapOf(keptPath to "etag-kept", skippedPath to "etag-skipped"),
                ),
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
            pendingStore.readDescriptor(SyncBackendType.S3)?.files?.single()?.remote?.locator shouldBe skippedRemotePath
            stateHolder.state.value shouldBe S3SyncState.ConflictDetected(conflictSet.copy(files = listOf(conflictSet.files[1])))
        }

    private fun `resolveConflicts preserves pending locator when it equals relative path`() =
        runTest {
            val path = "prefix/opaque-conflict.md"
            val localContent = "# local"
            val remoteContent = "# remote"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = path,
                    localContent = localContent,
                    remoteContent = remoteContent,
                    rootName = "s3-pending-conflict-locator",
                )
            pipeline.conflictStore.readDescriptor(SyncBackendType.S3)?.files?.single()?.remote?.locator shouldBe path
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = remoteContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            result shouldBe S3SyncResult.Success("Conflicts resolved")
            client.headKeys shouldBe listOf(path)
            client.getObjectKeys shouldBe listOf(path, path)
        }

    private fun `resolveConflicts rejects stale pending locator before head or get`() =
        runTest {
            val path = "notes/conflict.md"
            val localContent = "# local"
            val remoteContent = "# remote"
            val staleRemotePath = "old-prefix/opaque-conflict"
            val pipeline =
                materializedConflictPipeline(
                    path = path,
                    remotePath = staleRemotePath,
                    localContent = localContent,
                    remoteContent = remoteContent,
                    rootName = "s3-pending-conflict-stale-locator",
                    materializationPrefix = "old-prefix",
                )
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        throw AssertionError("stale pending locator must be rejected before HEAD: $key")
                    },
                    onGetObject = { key ->
                        throw AssertionError("stale pending locator must be rejected before GET: $key")
                    },
                )
            val resolver =
                createResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.conflictStore,
                )

            val result =
                resolver.resolveConflicts(
                    resolution =
                        SyncConflictResolution(
                            perFileChoices = mapOf(path to SyncConflictResolutionChoice.KEEP_REMOTE),
                        ),
                    conflictSet = pipeline.conflictSet,
                )

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION
            error.message shouldBe "S3 remote layout key is outside the configured prefix"
            pipeline.conflictStore.readDescriptor(SyncBackendType.S3)?.files?.single()?.remote?.locator shouldBe
                staleRemotePath
            client.headKeys shouldBe emptyList<String>()
            client.getObjectKeys shouldBe emptyList<String>()
            client.putKeys shouldBe emptyList()
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
                                isBinary = true,
                                local =
                                    PendingSyncSideMetadata(
                                        locator = path,
                                        contentHash = "local-fingerprint",
                                        lastModified = 10L,
                                        size = 4L,
                                        etag = "local-fingerprint",
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
            coEvery { localMediaSyncStore.md5Hex(path, any()) } returns "local-fingerprint"
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

    private fun `resolveReview rejects stale metadata key outside configured prefix before download`() =
        runTest {
            val path = "lomo/memo/review.md"
            val staleRemotePath = "old-prefix/opaque-review"
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
            val client = ConflictProbeS3Client()
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao =
                        ConflictMetadataDao(
                            initial = listOf(stableMetadata(path = path, remotePath = staleRemotePath)),
                        ),
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, review)

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION
            error.message shouldBe "S3 remote layout key is outside the configured prefix"
            client.getObjectKeys shouldBe emptyList()
            client.putKeys shouldBe emptyList()
            client.listCalls shouldBe 0
            (stateHolder.state.value is S3SyncState.Error).shouldBeTrue()
        }

    private fun `resolveReview preserves pending locator when it equals relative path`() =
        runTest {
            val path = "prefix/opaque-review.md"
            val localContent = "# local"
            val incomingContent = "# incoming"
            val pipeline =
                materializedReviewPipeline(
                    path = path,
                    remotePath = path,
                    localContent = localContent,
                    incomingContent = incomingContent,
                    rootName = "s3-pending-review-locator",
                )
            pipeline.reviewStore.readDescriptor(SyncBackendType.S3)?.items?.single()?.incoming?.locator shouldBe path
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe path
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = incomingContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe path
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = incomingContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.reviewStore,
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, pipeline.reviewSession)

            result shouldBe S3SyncResult.Success("Review resolved")
            client.headKeys shouldBe listOf(path)
            client.getObjectKeys shouldBe listOf(path, path)
        }

    private fun `resolveReview resolves memo review on first pass when only remote mtime normalization differs`() =
        runTest {
            val path = "notes/memo-mtime-review.md"
            val remotePath = "prefix/rclone/memo-mtime-review-key"
            val localContent = "# local"
            val incomingContent = "# incoming"
            val pipeline =
                materializedReviewPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = localContent,
                    incomingContent = incomingContent,
                    rootName = "s3-memo-mtime-review",
                )
            pipeline.reviewStore.readDescriptor(SyncBackendType.S3)?.items?.single()?.incoming?.lastModified shouldBe 20L
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        // Unchanged object (etag/size/content), but the app-embedded mtime resolves to a
                        // different millisecond value than the raw lastModified captured at preview time.
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = incomingContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = mapOf("mtime" to "99"),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = mapOf("mtime" to "99"),
                            bytes = incomingContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.reviewStore,
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, pipeline.reviewSession)

            result shouldBe S3SyncResult.Success("Review resolved")
            client.putKeys shouldBe emptyList()
            (stateHolder.state.value is S3SyncState.Success).shouldBeTrue()
        }

    private fun `resolveReview resolves binary incoming side when side metadata matches`() =
        runTest {
            val path = "assets/review.png"
            val remotePath = "prefix/rclone/review-opaque"
            val localContent = "local-binary"
            val incomingContent = "incoming-binary"
            val pipeline =
                materializedReviewPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = localContent,
                    incomingContent = incomingContent,
                    rootName = "s3-binary-review-resolve",
                )
            val descriptor = requireNotNull(pipeline.reviewStore.readDescriptor(SyncBackendType.S3))
            descriptor.items.single().local.contentHash shouldBe localContent.toByteArray(Charsets.UTF_8).md5Hex()
            descriptor.items.single().incoming.locator shouldBe remotePath
            pipeline.reviewSession.items.single().localContent shouldBe null
            pipeline.reviewSession.items.single().incomingContent shouldBe null
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        key shouldBe remotePath
                        S3RemoteObject(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            size = incomingContent.toByteArray(Charsets.UTF_8).size.toLong(),
                            metadata = emptyMap(),
                        )
                    },
                    onGetObject = { key ->
                        key shouldBe remotePath
                        S3SmallObjectPayload(
                            key = key,
                            eTag = "etag-remote",
                            lastModified = 20L,
                            metadata = emptyMap(),
                            bytes = incomingContent.toByteArray(Charsets.UTF_8),
                        )
                    },
                )
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.reviewStore,
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, pipeline.reviewSession)

            result shouldBe S3SyncResult.Success("Review resolved")
            client.headKeys shouldBe listOf(remotePath)
            client.getObjectKeys shouldBe listOf(remotePath)
            client.putKeys shouldBe emptyList()
            pipeline.reviewStore.readDescriptor(SyncBackendType.S3) shouldBe null
        }

    private fun `resolveReview rejects incomplete binary incoming descriptor before head or download`() =
        runTest {
            val path = "assets/review.png"
            val remotePath = "prefix/rclone/review-opaque"
            val pipeline =
                materializedReviewPipeline(
                    path = path,
                    remotePath = remotePath,
                    localContent = "local-binary",
                    incomingContent = "incoming-binary",
                    rootName = "s3-binary-review-incomplete-incoming",
                )
            val descriptor = requireNotNull(pipeline.reviewStore.readDescriptor(SyncBackendType.S3))
            pipeline.reviewStore.writeDescriptor(
                descriptor.copy(
                    items =
                        descriptor.items.map { item ->
                            item.copy(incoming = item.incoming.copy(size = null))
                        },
                ),
            )
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        throw AssertionError("incomplete incoming metadata must reject before HEAD: $key")
                    },
                    onGetObject = { key ->
                        throw AssertionError("incomplete incoming metadata must reject before GET: $key")
                    },
                )
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.reviewStore,
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, pipeline.reviewSession)

            result shouldBe S3SyncResult.Error("Pending S3 review session requires rebuild: STALE_REMOTE")
            client.headKeys shouldBe emptyList<String>()
            client.getObjectKeys shouldBe emptyList<String>()
            client.putKeys shouldBe emptyList()
        }

    private fun `resolveReview rejects stale pending locator before head or get`() =
        runTest {
            val path = "notes/review.md"
            val localContent = "# local"
            val incomingContent = "# incoming"
            val staleRemotePath = "old-prefix/opaque-review"
            val pipeline =
                materializedReviewPipeline(
                    path = path,
                    remotePath = staleRemotePath,
                    localContent = localContent,
                    incomingContent = incomingContent,
                    rootName = "s3-pending-review-stale-locator",
                    materializationPrefix = "old-prefix",
                )
            val client =
                ConflictProbeS3Client(
                    onGetMetadata = { key ->
                        throw AssertionError("stale pending locator must be rejected before HEAD: $key")
                    },
                    onGetObject = { key ->
                        throw AssertionError("stale pending locator must be rejected before GET: $key")
                    },
                )
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao = ConflictMetadataDao(),
                    pendingStore = pipeline.reviewStore,
                )
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_INCOMING))

            val result = resolver.resolveReview(resolution, pipeline.reviewSession)

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION
            error.message shouldBe "S3 remote layout key is outside the configured prefix"
            pipeline.reviewStore.readDescriptor(SyncBackendType.S3)?.items?.single()?.incoming?.locator shouldBe
                staleRemotePath
            client.headKeys shouldBe emptyList<String>()
            client.getObjectKeys shouldBe emptyList<String>()
            client.putKeys shouldBe emptyList()
        }

    private fun `resolveReview rejects stale metadata key outside configured prefix before upload`() =
        runTest {
            val path = "lomo/memo/review-upload.md"
            val staleRemotePath = "old-prefix/opaque-review-upload"
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
            val client = ConflictProbeS3Client()
            val resolver =
                createReviewResolver(
                    client = client,
                    metadataDao =
                        ConflictMetadataDao(
                            initial = listOf(stableMetadata(path = path, remotePath = staleRemotePath)),
                        ),
                )
            coEvery {
                markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "review-upload.md")
            } returns "# local"
            coEvery {
                markdownStorageDataSource.getFileMetadataIn(MemoDirectoryType.MAIN, "review-upload.md")
            } returns FileMetadata(filename = "review-upload.md", lastModified = 50L)
            val resolution = SyncReviewResolution(perItemChoices = mapOf(path to SyncReviewResolutionChoice.KEEP_LOCAL))

            val result = resolver.resolveReview(resolution, review)

            val error = result as S3SyncResult.Error
            error.code shouldBe S3SyncErrorCode.REMOTE_LAYOUT_VIOLATION
            error.message shouldBe "S3 remote layout key is outside the configured prefix"
            client.putKeys shouldBe emptyList()
            client.getObjectKeys shouldBe emptyList()
            client.listCalls shouldBe 0
            (stateHolder.state.value is S3SyncState.Error).shouldBeTrue()
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
        support = S3SyncRepositorySupport(
                runtime = runtime,
                credentialRepository = testS3CredentialRepository(),
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
            )
        return S3ConflictResolver(
            runtime = runtime,
            support = support,
            encodingSupport = encodingSupport,
            objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
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
        val support = S3SyncRepositorySupport(
                runtime = runtime,
                credentialRepository = testS3CredentialRepository(),
                securitySessionPolicy = AuthorizedCredentialReadSessionPolicy,
            )
        return S3ReviewResolver(
            runtime = runtime,
            support = support,
            encodingSupport = encodingSupport,
            objectKeyPolicy = S3RemoteObjectKeyPolicy(encodingSupport),
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

    private fun SyncConflictSet.toPendingDescriptor(
        remoteLocators: Map<String, String>,
        localLastModified: Long,
        remoteLastModified: Long,
        localLastModifiedByPath: Map<String, Long> = emptyMap(),
        remoteLastModifiedByPath: Map<String, Long> = emptyMap(),
        remoteEtags: Map<String, String>,
    ): PendingSyncConflictDescriptor =
        PendingSyncConflictDescriptor(
            source = source,
            workspaceGeneration = "test",
            files =
                files.map { file ->
                    val localBytes = file.localContent?.toByteArray(Charsets.UTF_8)
                    val remoteBytes = file.remoteContent?.toByteArray(Charsets.UTF_8)
                    PendingSyncConflictFileDescriptor(
                        relativePath = file.relativePath,
                        isBinary = file.isBinary,
                        local =
                            PendingSyncSideMetadata(
                                locator = file.relativePath,
                                contentHash = localBytes?.md5Hex(),
                                lastModified = localLastModifiedByPath[file.relativePath] ?: localLastModified,
                                size = localBytes?.size?.toLong(),
                                etag = localBytes?.md5Hex(),
                            ),
                        remote =
                            PendingSyncSideMetadata(
                                locator = requireNotNull(remoteLocators[file.relativePath]),
                                contentHash = remoteBytes?.md5Hex(),
                                lastModified = remoteLastModifiedByPath[file.relativePath] ?: remoteLastModified,
                                size = remoteBytes?.size?.toLong(),
                                etag = requireNotNull(remoteEtags[file.relativePath]),
                            ),
                    )
                },
            timestamp = timestamp,
            validationStatus = PendingSyncValidationStatus.PENDING_RELOAD,
        )

    private suspend fun materializedConflictPipeline(
        path: String,
        remotePath: String,
        localContent: String,
        remoteContent: String,
        rootName: String,
        materializationPrefix: String = "prefix",
    ): MaterializedConflictPipeline {
        val root = newTempFolder(rootName)
        writeLocalPipelineFile(root = root, path = path, content = localContent)
        every { dataStore.s3LocalSyncDirectory } returns flowOf(root.absolutePath)
        val materialization =
            requireNotNull(
                buildS3PendingConflictMaterialization(
                    actions = listOf(conflictAction(path)),
                    client =
                        ConflictProbeS3Client(
                            onGetObject = { key ->
                                key shouldBe remotePath
                                S3SmallObjectPayload(
                                    key = key,
                                    eTag = "etag-remote",
                                    lastModified = 20L,
                                    metadata = emptyMap(),
                                    bytes = remoteContent.toByteArray(Charsets.UTF_8),
                                )
                            },
                        ),
                    layout = materializationLayout,
                    config = materializationConfig(prefix = materializationPrefix),
                    remoteFiles = mapOf(path to remoteFile(path = path, remotePath = remotePath, content = remoteContent)),
                    fileBridgeScope = materializationFileBridgeScope(root),
                    mode = materializationFileVaultRoot(root),
                    encodingSupport = S3SyncEncodingSupport(),
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(S3SyncEncodingSupport()),
                ),
            )
        val store = RoomPendingSyncConflictStore(FakePipelinePendingSyncConflictDao(), PipelineGenerationProvider)
        store.writeDescriptor(materialization.descriptor)
        return MaterializedConflictPipeline(
            conflictSet = materialization.conflictSet,
            conflictStore = store,
            root = root,
        )
    }

    private suspend fun materializedReviewPipeline(
        path: String,
        remotePath: String,
        localContent: String,
        incomingContent: String,
        rootName: String,
        materializationPrefix: String = "prefix",
    ): MaterializedReviewPipeline {
        val conflictPipeline =
            materializedConflictPipeline(
                path = path,
                remotePath = remotePath,
                localContent = localContent,
                remoteContent = incomingContent,
                rootName = rootName,
                materializationPrefix = materializationPrefix,
            )
        val reviewMaterialization =
            S3PendingConflictMaterialization(
                conflictSet = conflictPipeline.conflictSet,
                descriptor = requireNotNull(conflictPipeline.conflictStore.readDescriptor(SyncBackendType.S3)),
            ).toInitialImportReviewMaterialization()
        val store = RoomPendingSyncReviewStore(FakePipelinePendingSyncReviewDao(), PipelineGenerationProvider)
        store.writeDescriptor(reviewMaterialization.descriptor)
        return MaterializedReviewPipeline(
            reviewSession = reviewMaterialization.reviewSession,
            reviewStore = store,
        )
    }

    private fun writeLocalPipelineFile(
        root: File,
        path: String,
        content: String,
    ) {
        root.resolve(path).also { file ->
            file.parentFile?.mkdirs()
            file.writeText(content)
            file.setLastModified(10L)
        }
    }

    private fun conflictAction(path: String): S3SyncAction =
        S3SyncAction(
            path = path,
            direction = S3SyncDirection.CONFLICT,
            reason = S3SyncReason.CONFLICT,
        )

    private fun remoteFile(
        path: String,
        remotePath: String,
        content: String,
    ): RemoteS3File =
        RemoteS3File(
            path = path,
            etag = "etag-remote",
            lastModified = 20L,
            size = content.toByteArray(Charsets.UTF_8).size.toLong(),
            remotePath = remotePath,
        )

    private fun materializationConfig(prefix: String): S3ResolvedConfig =
        S3ResolvedConfig(
            endpointUrl = "https://s3.example.com",
            region = "us-east-1",
            bucket = "bucket",
            prefix = prefix,
            accessKeyId = "access",
            secretAccessKey = "secret",
            sessionToken = null,
            pathStyle = S3PathStyle.PATH_STYLE,
            encryptionMode = S3EncryptionMode.NONE,
            encryptionPassword = null,
        )

    private fun materializationFileVaultRoot(root: File): S3LocalSyncMode.FileVaultRoot =
        S3LocalSyncMode.FileVaultRoot(
            rootDir = root,
            memoRelativeDir = null,
            imageRelativeDir = null,
            voiceRelativeDir = null,
            legacyRemoteCompatibility = false,
        )

    private fun materializationFileBridgeScope(root: File): S3SyncFileBridgeScope =
        S3SyncFileBridgeScope(
            runtime = mockk(),
            encodingSupport = S3SyncEncodingSupport(),
            safTreeAccess = UnsupportedS3SafTreeAccess,
            mode = materializationFileVaultRoot(root),
        )

    private fun numberedLines(prefix: String): String =
        (0..1_000).joinToString("\n") { index -> "$prefix-$index" }

    private fun newTempFolder(name: String): java.io.File {
        val folder = tempFolder ?: KotestTemporaryFolder().also { tempFolder = it }
        return folder.newFolder(name)
    }
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

private data class MaterializedConflictPipeline(
    val conflictSet: SyncConflictSet,
    val conflictStore: PendingSyncConflictStore,
    val root: File,
)

private data class MaterializedReviewPipeline(
    val reviewSession: SyncReviewSession,
    val reviewStore: PendingSyncReviewStore,
)

private object PipelineGenerationProvider : WorkspaceSyncGenerationProvider {
    override suspend fun activeGeneration(): WorkspaceSyncGeneration = WorkspaceSyncGeneration("test")
}

private class FakePipelinePendingSyncConflictDao : PendingSyncConflictDao {
    private val entries = linkedMapOf<Pair<String, String>, PendingSyncConflictEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncConflictEntity? = entries[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncConflictEntity) {
        entries[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entries.remove(workspaceGeneration to backend)
    }
}

private class FakePipelinePendingSyncReviewDao : PendingSyncReviewDao {
    private val entries = linkedMapOf<Pair<String, String>, PendingSyncReviewEntity>()

    override suspend fun getByBackend(
        backend: String,
        workspaceGeneration: String,
    ): PendingSyncReviewEntity? = entries[workspaceGeneration to backend]

    override suspend fun upsert(entity: PendingSyncReviewEntity) {
        entries[entity.workspaceGeneration to entity.backend] = entity
    }

    override suspend fun deleteByBackend(
        backend: String,
        workspaceGeneration: String,
    ) {
        entries.remove(workspaceGeneration to backend)
    }
}

private class ConflictProbeS3Client(
    private val onGetMetadata: suspend (String) -> S3RemoteObject? = { null },
    private val onGetObject: suspend (String) -> S3SmallObjectPayload = {
        throw AssertionError("Unexpected getObject for $it")
    },
    private val onPutObject: suspend (String, ByteArray) -> S3PutObjectResult = { _, _ ->
        S3PutObjectResult(eTag = "etag-uploaded")
    },
) : LomoS3Client {
    var listCalls: Int = 0
    val headKeys = mutableListOf<String>()
    val getObjectKeys = mutableListOf<String>()
    val putKeys = mutableListOf<String>()

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun getObjectMetadata(key: String): S3RemoteObject? {
        headKeys += key
        return onGetMetadata(key)
    }

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
        ifMatch: String?,
        ifNoneMatch: String?,
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
        ifMatch: String?,
        ifNoneMatch: String?,
    ): com.lomo.data.s3.S3PutObjectResult =
        putSmallObject(key, file.readBytes(), contentType, metadata, ifMatch, ifNoneMatch)

    override suspend fun deleteObject(key: String) =
        throw AssertionError("Conflict resolution should not delete remote objects")

    override fun close() = Unit
}

private class S3TrackingPendingSyncConflictStore : PendingSyncConflictStore {
    var descriptor: PendingSyncConflictDescriptor? = null
    val clearCalls = mutableListOf<SyncBackendType>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncConflictDescriptor? = descriptor

    override suspend fun write(conflictSet: SyncConflictSet) = Unit

    override suspend fun writeDescriptor(descriptor: PendingSyncConflictDescriptor) {
        this.descriptor = descriptor
    }

    override suspend fun clear(source: SyncBackendType) {
        clearCalls += source
    }
}

private class S3TrackingPendingSyncReviewStore : PendingSyncReviewStore {
    var descriptor: PendingSyncReviewDescriptor? = null
    val clearCalls = mutableListOf<SyncBackendType>()

    override suspend fun readDescriptor(source: SyncBackendType): PendingSyncReviewDescriptor? = descriptor

    override suspend fun write(review: SyncReviewSession) = Unit

    override suspend fun writeDescriptor(descriptor: PendingSyncReviewDescriptor) {
        this.descriptor = descriptor
    }

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

    override suspend fun fingerprintFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String? = readFileIn(directory, filename)?.toByteArray(Charsets.UTF_8)?.md5Hex()

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
