package com.lomo.data.repository

import com.lomo.data.local.dao.PendingSyncReviewDao
import com.lomo.data.local.entity.PendingSyncReviewEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncReviewItem
import com.lomo.domain.model.SyncReviewItemState
import com.lomo.domain.model.SyncReviewSession
import com.lomo.domain.model.SyncReviewSessionKind
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: RoomPendingSyncReviewStore
 * - Capability: Persist pending sync review sessions through a descriptor-only review-owned table and payload while preserving S3-discovered incoming locators supplied by production materialization.
 * - Scenarios:
 *   - Given a pending review session, when written, then raw local/incoming markdown is not stored in Room JSON.
 *   - Given a S3 binary/non-memo review session without side descriptors, when written through the
 *     generic session API, then persistence rejects it instead of deriving side identity from nullable text content.
 *   - Given S3 materializes an initial-import review from a discovered RemoteS3File.remotePath, when written as a descriptor, then the incoming locator is persisted exactly.
 *   - Given S3 materializes a binary/non-memo initial-import review, when written as a descriptor,
 *     then incoming remote size/etag/mtime/locator are persisted without text content.
 *   - Given multiple backend sessions, when one backend is cleared, then only that review session is removed.
 * - Observable outcomes: descriptor metadata, absent raw content in payload JSON, backend-scoped clear behavior, and exact S3 incoming locator persistence.
 * - TDD proof: Fails before the fix because binary S3 review materialization stores null local contentHash
 *   instead of the local file fingerprint and relies on nullable text content.
 * - TDD proof: RED Report 10 tail failed because write(SyncReviewSession) accepted a S3 binary review
 *   with null local/incoming content and persisted null side metadata.
 * - Excludes: Room framework internals, sync repository orchestration, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: Data layer module gained app update install persistence, migration archive staging workspace, settings preference repos, and strengthened sync conflict store contracts.
 * - Old behavior/assertion being replaced: previous data layer tests relied on older repository contracts and store implementations before these modules were restructured.
 * - Why old assertion is no longer correct: new modules introduce typed credential reads, positional memo identities, and staged migration/restore plans that change observable data behavior.
 * - Coverage preserved by: all existing repository scenarios retained; new scenarios added for install persistence, staging workspace, preference repos, and conflict store contracts.
 * - Why this is not fitting the test to the implementation: tests verify observable repository store outcomes, not internal implementation details.
 */
class PendingSyncReviewStoreTest : DataFunSpec() {
    init {
        var tempFolder: KotestTemporaryFolder? = null

        beforeTest {
            tempFolder = KotestTemporaryFolder()
        }

        afterTest {
            tempFolder?.cleanup()
            tempFolder = null
        }

        test("write stores descriptor without full review content") {
            `write stores descriptor without full review content`()
        }

        test("write rejects S3 binary review without explicit side descriptor") {
            `write rejects S3 binary review without explicit side descriptor`()
        }

        test("writeDescriptor stores S3 materialized review locator exactly") {
            `writeDescriptor stores S3 materialized review locator exactly`(requireNotNull(tempFolder))
        }

        test("writeDescriptor stores S3 binary review side metadata without text content") {
            `writeDescriptor stores S3 binary review side metadata without text content`(requireNotNull(tempFolder))
        }

        test("clear removes only targeted backend review session") {
            `clear removes only targeted backend review session`()
        }
    }

    private fun `write stores descriptor without full review content`() =
        runTest {
            val dao = FakePendingSyncReviewDao()
            val store = RoomPendingSyncReviewStore(dao, ReviewStoreTestGenerationProvider())
            val largeLocal = "local-review-content-".repeat(512)
            val largeIncoming = "incoming-review-content-".repeat(512)
            val review =
                reviewSession(
                    source = SyncBackendType.INBOX,
                    path = "inbox/2026_05_26.md",
                    localContent = largeLocal,
                    incomingContent = largeIncoming,
                )

            store.write(review)

            val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.INBOX))
            descriptor.source shouldBe SyncBackendType.INBOX
            descriptor.items.single().relativePath shouldBe "inbox/2026_05_26.md"
            descriptor.items.single().state shouldBe SyncReviewItemState.READY_TO_IMPORT
            descriptor.items.single().local.size shouldBe largeLocal.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.items.single().incoming.size shouldBe largeIncoming.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.items.single().local.etag shouldBe descriptor.items.single().local.contentHash
            descriptor.items.single().incoming.etag shouldBe descriptor.items.single().incoming.contentHash
            dao.payloadFor(SyncBackendType.INBOX).shouldNotContain(largeLocal)
            dao.payloadFor(SyncBackendType.INBOX).shouldNotContain(largeIncoming)
        }

    private fun `write rejects S3 binary review without explicit side descriptor`() =
        runTest {
            val store = RoomPendingSyncReviewStore(FakePendingSyncReviewDao(), ReviewStoreTestGenerationProvider())
            val review =
                SyncReviewSession(
                    source = SyncBackendType.S3,
                    items =
                        listOf(
                            SyncReviewItem(
                                relativePath = "assets/review.png",
                                localContent = null,
                                incomingContent = null,
                                isBinary = true,
                                localLastModified = 10L,
                                incomingLastModified = 20L,
                                state = SyncReviewItemState.READY_TO_IMPORT,
                            ),
                        ),
                    timestamp = 123L,
                    kind = SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW,
                )

            shouldThrow<IllegalArgumentException> {
                store.write(review)
            }.message shouldBe
                "S3 binary/non-memo pending reviews require explicit side descriptors from materialization"
        }

    private fun `writeDescriptor stores S3 materialized review locator exactly`(tempFolder: KotestTemporaryFolder) =
        runTest {
            val dao = FakePendingSyncReviewDao()
            val store = RoomPendingSyncReviewStore(dao, ReviewStoreTestGenerationProvider())
            val relativePath = "notes/review.md"
            val discoveredRemotePath = "prefix/rclone/review-opaque"
            val localContent = "# local"
            val incomingContent = "# incoming"
            val root = tempFolder.newFolder("s3-review-root")
            root.resolve(relativePath).also { file ->
                requireNotNull(file.parentFile).mkdirs()
                file.writeText(localContent)
                file.setLastModified(10L)
            }
            val conflictMaterialization =
                requireNotNull(
                    buildS3PendingConflictMaterialization(
                        actions =
                            listOf(
                                S3SyncAction(
                                    path = relativePath,
                                    direction = S3SyncDirection.CONFLICT,
                                    reason = S3SyncReason.CONFLICT,
                                ),
                            ),
                        client = PendingReviewStoreProbeS3Client(discoveredRemotePath, incomingContent),
                        layout = reviewTestSyncLayout,
                        config = reviewTestS3Config,
                        remoteFiles =
                            mapOf(
                                relativePath to
                                    RemoteS3File(
                                        path = relativePath,
                                        etag = "incoming-etag",
                                        lastModified = 20L,
                                        size = incomingContent.toByteArray(Charsets.UTF_8).size.toLong(),
                                        remotePath = discoveredRemotePath,
                                    ),
                            ),
                        fileBridgeScope = reviewFileBridgeScope(root),
                        mode = reviewFileVaultRoot(root),
                        encodingSupport = S3SyncEncodingSupport(),
                        objectKeyPolicy = S3RemoteObjectKeyPolicy(S3SyncEncodingSupport()),
                    ),
                )
            val reviewMaterialization = conflictMaterialization.toInitialImportReviewMaterialization()

            store.writeDescriptor(reviewMaterialization.descriptor)

            val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.S3))
            descriptor.kind shouldBe SyncReviewSessionKind.INITIAL_IMPORT_PREVIEW
            descriptor.items.single().relativePath shouldBe relativePath
            descriptor.items.single().incoming.locator shouldBe discoveredRemotePath
            descriptor.items.single().incoming.etag shouldBe "incoming-etag"
            descriptor.items.single().incoming.size shouldBe incomingContent.toByteArray(Charsets.UTF_8).size.toLong()
            dao.payloadFor(SyncBackendType.S3).shouldNotContain(incomingContent)
        }

    private fun `writeDescriptor stores S3 binary review side metadata without text content`(
        tempFolder: KotestTemporaryFolder,
    ) = runTest {
        val dao = FakePendingSyncReviewDao()
        val store = RoomPendingSyncReviewStore(dao, ReviewStoreTestGenerationProvider())
        val relativePath = "assets/review.png"
        val discoveredRemotePath = "prefix/rclone/review-binary-opaque"
        val localBytes = byteArrayOf(3, 1, 4)
        val incomingBytes = byteArrayOf(2, 7, 1, 8)
        val root = tempFolder.newFolder("s3-binary-review-root")
        root.resolve(relativePath).also { file ->
            requireNotNull(file.parentFile).mkdirs()
            file.writeBytes(localBytes)
            file.setLastModified(10L)
        }
        val conflictMaterialization =
            requireNotNull(
                buildS3PendingConflictMaterialization(
                    actions = listOf(reviewConflictAction(relativePath)),
                    client = PendingReviewStoreProbeS3Client(discoveredRemotePath, incomingBytes),
                    layout = reviewTestSyncLayout,
                    config = reviewTestS3Config,
                    remoteFiles =
                        mapOf(
                            relativePath to
                                RemoteS3File(
                                    path = relativePath,
                                    etag = "incoming-etag",
                                    lastModified = 20L,
                                    size = incomingBytes.size.toLong(),
                                    remotePath = discoveredRemotePath,
                                ),
                        ),
                    fileBridgeScope = reviewFileBridgeScope(root),
                    mode = reviewFileVaultRoot(root),
                    encodingSupport = S3SyncEncodingSupport(),
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(S3SyncEncodingSupport()),
                ),
            )
        val reviewMaterialization = conflictMaterialization.toInitialImportReviewMaterialization()

        store.writeDescriptor(reviewMaterialization.descriptor)

        reviewMaterialization.reviewSession.items.single().localContent.shouldBeNull()
        reviewMaterialization.reviewSession.items.single().incomingContent.shouldBeNull()
        val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.S3))
        descriptor.items.single().local.lastModified shouldBe 10L
        descriptor.items.single().local.size shouldBe localBytes.size.toLong()
        descriptor.items.single().local.contentHash shouldBe localBytes.md5Hex()
        descriptor.items.single().incoming.locator shouldBe discoveredRemotePath
        descriptor.items.single().incoming.lastModified shouldBe 20L
        descriptor.items.single().incoming.size shouldBe incomingBytes.size.toLong()
        descriptor.items.single().incoming.etag shouldBe "incoming-etag"
        dao.payloadFor(SyncBackendType.S3).shouldNotContain(String(incomingBytes, Charsets.UTF_8))
    }

    private fun `clear removes only targeted backend review session`() =
        runTest {
            val store = RoomPendingSyncReviewStore(FakePendingSyncReviewDao(), ReviewStoreTestGenerationProvider())
            val inboxReview = reviewSession(source = SyncBackendType.INBOX, path = "inbox/2026_05_26.md")
            val webDavReview = reviewSession(source = SyncBackendType.WEBDAV, path = "lomo/memo/remote.md")
            store.write(inboxReview)
            store.write(webDavReview)

            store.clear(SyncBackendType.INBOX)

            store.readDescriptor(SyncBackendType.INBOX).shouldBeNull()
            store.readDescriptor(SyncBackendType.WEBDAV)?.source shouldBe webDavReview.source
        }

    private fun reviewSession(
        source: SyncBackendType,
        path: String,
        localContent: String = "local",
        incomingContent: String = "incoming",
    ): SyncReviewSession =
        SyncReviewSession(
            source = source,
            items =
                listOf(
                    SyncReviewItem(
                        relativePath = path,
                        localContent = localContent,
                        incomingContent = incomingContent,
                        isBinary = false,
                        localLastModified = 10L,
                        incomingLastModified = 20L,
                        state = SyncReviewItemState.READY_TO_IMPORT,
                        message = "ready",
                    ),
                ),
            timestamp = 123L,
            kind = SyncReviewSessionKind.SYNC_INBOX_IMPORT_REVIEW,
        )
}

private class FakePendingSyncReviewDao : PendingSyncReviewDao {
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

    fun payloadFor(source: SyncBackendType): String =
        requireNotNull(entries["workspace-test" to source.name]).payloadJson
}

private class ReviewStoreTestGenerationProvider : WorkspaceSyncGenerationProvider {
    override suspend fun activeGeneration(): WorkspaceSyncGeneration = WorkspaceSyncGeneration("workspace-test")
}

private val reviewTestSyncLayout =
    SyncDirectoryLayout(
        memoFolder = "memo",
        imageFolder = "images",
        voiceFolder = "voice",
        allSameDirectory = false,
    )

private val reviewTestS3Config =
    S3ResolvedConfig(
        endpointUrl = "https://s3.example.com",
        region = "us-east-1",
        bucket = "bucket",
        prefix = "prefix",
        accessKeyId = "access",
        secretAccessKey = "secret",
        sessionToken = null,
        pathStyle = S3PathStyle.PATH_STYLE,
        encryptionMode = S3EncryptionMode.NONE,
        encryptionPassword = null,
    )

private fun reviewFileVaultRoot(root: File): S3LocalSyncMode.FileVaultRoot =
    S3LocalSyncMode.FileVaultRoot(
        rootDir = root,
        memoRelativeDir = null,
        imageRelativeDir = null,
        voiceRelativeDir = null,
        legacyRemoteCompatibility = false,
    )

private fun reviewFileBridgeScope(root: File): S3SyncFileBridgeScope =
    S3SyncFileBridgeScope(
        runtime = mockk(),
        encodingSupport = S3SyncEncodingSupport(),
        safTreeAccess = UnsupportedS3SafTreeAccess,
        mode = reviewFileVaultRoot(root),
    )

private class PendingReviewStoreProbeS3Client(
    private val expectedKey: String,
    private val incomingContent: ByteArray,
) : LomoS3Client {
    constructor(
        expectedKey: String,
        incomingContent: String,
    ) : this(expectedKey, incomingContent.toByteArray(Charsets.UTF_8))

    override suspend fun verifyAccess(prefix: String) = Unit

    override suspend fun list(
        prefix: String,
        maxKeys: Int?,
    ): List<S3RemoteObject> = emptyList()

    override suspend fun listPage(
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): S3RemoteListPage = S3RemoteListPage(objects = emptyList(), nextContinuationToken = null)

    override suspend fun getObjectToFile(
        key: String,
        destination: File,
    ): S3RemoteObject = error("Pending review store materialization test does not download files")

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        key shouldBe expectedKey
        return S3SmallObjectPayload(
            key = key,
            eTag = "incoming-etag",
            lastModified = 20L,
            metadata = emptyMap(),
            bytes = incomingContent,
        )
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = error("Pending review store materialization test does not upload")

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = error("Pending review store materialization test does not upload files")

    override suspend fun deleteObject(key: String) {
        error("Pending review store materialization test does not delete")
    }

    override fun close() = Unit
}

private fun reviewConflictAction(path: String): S3SyncAction =
    S3SyncAction(
        path = path,
        direction = S3SyncDirection.CONFLICT,
        reason = S3SyncReason.CONFLICT,
    )
