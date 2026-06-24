package com.lomo.data.repository

/*
 * Behavior Contract:
 * - Unit under test: RoomPendingSyncConflictStore
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: Persist pending sync conflict descriptors with side metadata without storing full content payloads, and preserve S3-discovered remote locators.
 *
 * Scenarios:
 * - Given a pending conflict set, when written, then raw local/remote content is not stored in Room JSON payload.
 * - Given a S3 binary/non-memo conflict without side descriptors, when written through the generic session API, then persistence rejects it.
 * - Given S3 materializes a conflict from a discovered RemoteS3File.remotePath, when written as a descriptor, then the remote locator is persisted exactly.
 * - Given multiple backend sessions, when one backend is cleared, then only that conflict session is removed.
 *
 * Observable outcomes:
 * - Descriptor metadata presence, absent raw content in payload JSON, backend-scoped clear behavior, and exact S3 remote locator persistence.
 *
 * TDD proof:
 * - Fails before the systemic fix because binary S3 materialization stores null local contentHash instead of the local file fingerprint.
 *
 * Excludes:
 * - Room framework internals, repository orchestration, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: Data layer module restructuring aligned with new repository contracts.
 * - Old behavior/assertion being replaced: previous assertions relied on older store implementations.
 * - Why old assertion is no longer correct: new module boundaries change observable store behavior.
 * - Coverage preserved by: all scenarios retained with updated contract assertions.
 * - Why this is not fitting the test to the implementation: tests verify externally observable store outcomes.
 */

import com.lomo.data.local.dao.PendingSyncConflictDao
import com.lomo.data.local.entity.PendingSyncConflictEntity
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.S3PutObjectResult
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3SmallObjectPayload
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.model.SyncConflictSet
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3SyncDirection
import com.lomo.domain.model.S3SyncReason
import com.lomo.domain.repository.WorkspaceSyncGeneration
import com.lomo.domain.repository.WorkspaceSyncGenerationProvider
import kotlinx.coroutines.test.runTest
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import java.io.File

class PendingSyncConflictStoreTest : DataFunSpec() {
    init {
        var tempFolder: KotestTemporaryFolder? = null

        beforeTest {
            tempFolder = KotestTemporaryFolder()
        }

        afterTest {
            tempFolder?.cleanup()
            tempFolder = null
        }

        test("write stores descriptor without full conflict content") {
            `write stores descriptor without full conflict content`()
        }

        test("write rejects S3 binary conflict without explicit side descriptor") {
            `write rejects S3 binary conflict without explicit side descriptor`()
        }

        test("writeDescriptor stores S3 materialized conflict locator exactly") {
            `writeDescriptor stores S3 materialized conflict locator exactly`(requireNotNull(tempFolder))
        }

        test("writeDescriptor stores S3 binary conflict side metadata without text content") {
            `writeDescriptor stores S3 binary conflict side metadata without text content`(requireNotNull(tempFolder))
        }

        test("clear removes only targeted backend session") { `clear removes only targeted backend session`() }
    }


    private fun `write stores descriptor without full conflict content`() =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao, ConflictStoreTestGenerationProvider())
            val largeLocal = "local-large-content-".repeat(512)
            val largeRemote = "remote-large-content-".repeat(512)
            val conflict =
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/note.md",
                                localContent = largeLocal,
                                remoteContent = largeRemote,
                                isBinary = false,
                                localLastModified = 10L,
                                remoteLastModified = 20L,
                            ),
                        ),
                    timestamp = 123L,
                )

            store.write(conflict)

            val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.S3))
            descriptor.source shouldBe SyncBackendType.S3
            descriptor.files.single().relativePath shouldBe "lomo/memo/note.md"
            descriptor.files.single().local.lastModified shouldBe 10L
            descriptor.files.single().remote.lastModified shouldBe 20L
            descriptor.files.single().local.size shouldBe largeLocal.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.files.single().remote.size shouldBe largeRemote.toByteArray(Charsets.UTF_8).size.toLong()
            descriptor.files.single().local.etag shouldBe descriptor.files.single().local.contentHash
            descriptor.files.single().remote.etag shouldBe descriptor.files.single().remote.contentHash
            dao.payloadFor(SyncBackendType.S3).shouldNotContain(largeLocal)
            dao.payloadFor(SyncBackendType.S3).shouldNotContain(largeRemote)
        }

    private fun `write rejects S3 binary conflict without explicit side descriptor`() =
        runTest {
            val store = RoomPendingSyncConflictStore(FakePendingSyncConflictDao(), ConflictStoreTestGenerationProvider())
            val conflict =
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "assets/photo.png",
                                localContent = null,
                                remoteContent = null,
                                isBinary = true,
                                localLastModified = 10L,
                                remoteLastModified = 20L,
                            ),
                        ),
                    timestamp = 123L,
                )

            shouldThrow<IllegalArgumentException> {
                store.write(conflict)
            }.message shouldBe
                "S3 binary/non-memo pending conflicts require explicit side descriptors from materialization"
        }

    private fun `writeDescriptor stores S3 materialized conflict locator exactly`(tempFolder: KotestTemporaryFolder) =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao, ConflictStoreTestGenerationProvider())
            val relativePath = "notes/opaque-equals-relative.md"
            val discoveredRemotePath = "prefix/rclone/opaque-key"
            val localContent = "# local"
            val remoteContent = "# remote"
            val root = tempFolder.newFolder("s3-conflict-root")
            root.resolve(relativePath).also { file ->
                requireNotNull(file.parentFile).mkdirs()
                file.writeText(localContent)
                file.setLastModified(10L)
            }

            val materialization =
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
                        client = PendingStoreProbeS3Client(discoveredRemotePath, remoteContent),
                        layout = testSyncLayout,
                        config = testS3Config,
                        remoteFiles =
                            mapOf(
                                relativePath to
                                    RemoteS3File(
                                        path = relativePath,
                                        etag = "remote-etag",
                                        lastModified = 20L,
                                        size = remoteContent.toByteArray(Charsets.UTF_8).size.toLong(),
                                        remotePath = discoveredRemotePath,
                                    ),
                            ),
                        fileBridgeScope = fileBridgeScope(root),
                        mode = fileVaultRoot(root),
                        encodingSupport = S3SyncEncodingSupport(),
                        objectKeyPolicy = S3RemoteObjectKeyPolicy(S3SyncEncodingSupport()),
                    ),
                )

            store.writeDescriptor(materialization.descriptor)

            val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.S3))
            descriptor.files.single().relativePath shouldBe relativePath
            descriptor.files.single().remote.locator shouldBe discoveredRemotePath
            descriptor.files.single().remote.etag shouldBe "remote-etag"
            descriptor.files.single().remote.size shouldBe remoteContent.toByteArray(Charsets.UTF_8).size.toLong()
            dao.payloadFor(SyncBackendType.S3).shouldNotContain(remoteContent)
        }

    private fun `writeDescriptor stores S3 binary conflict side metadata without text content`(
        tempFolder: KotestTemporaryFolder,
    ) = runTest {
        val dao = FakePendingSyncConflictDao()
        val store = RoomPendingSyncConflictStore(dao, ConflictStoreTestGenerationProvider())
        val relativePath = "assets/photo.png"
        val discoveredRemotePath = "prefix/rclone/photo-opaque"
        val localBytes = byteArrayOf(1, 2, 3, 4)
        val remoteBytes = byteArrayOf(9, 8, 7, 6, 5)
        val root = tempFolder.newFolder("s3-binary-conflict-root")
        root.resolve(relativePath).also { file ->
            requireNotNull(file.parentFile).mkdirs()
            file.writeBytes(localBytes)
            file.setLastModified(10L)
        }

        val materialization =
            requireNotNull(
                buildS3PendingConflictMaterialization(
                    actions = listOf(conflictAction(relativePath)),
                    client = PendingStoreProbeS3Client(discoveredRemotePath, remoteBytes),
                    layout = testSyncLayout,
                    config = testS3Config,
                    remoteFiles =
                        mapOf(
                            relativePath to
                                RemoteS3File(
                                    path = relativePath,
                                    etag = "remote-etag",
                                    lastModified = 20L,
                                    size = remoteBytes.size.toLong(),
                                    remotePath = discoveredRemotePath,
                                ),
                        ),
                    fileBridgeScope = fileBridgeScope(root),
                    mode = fileVaultRoot(root),
                    encodingSupport = S3SyncEncodingSupport(),
                    objectKeyPolicy = S3RemoteObjectKeyPolicy(S3SyncEncodingSupport()),
                ),
            )

        store.writeDescriptor(materialization.descriptor)

        materialization.conflictSet.files.single().localContent.shouldBeNull()
        materialization.conflictSet.files.single().remoteContent.shouldBeNull()
        val descriptor = requireNotNull(store.readDescriptor(SyncBackendType.S3))
        descriptor.files.single().local.lastModified shouldBe 10L
        descriptor.files.single().local.size shouldBe localBytes.size.toLong()
        descriptor.files.single().local.contentHash shouldBe localBytes.md5Hex()
        descriptor.files.single().remote.locator shouldBe discoveredRemotePath
        descriptor.files.single().remote.lastModified shouldBe 20L
        descriptor.files.single().remote.size shouldBe remoteBytes.size.toLong()
        descriptor.files.single().remote.etag shouldBe "remote-etag"
        dao.payloadFor(SyncBackendType.S3).shouldNotContain(String(remoteBytes, Charsets.UTF_8))
    }

    private fun `clear removes only targeted backend session`() =
        runTest {
            val dao = FakePendingSyncConflictDao()
            val store = RoomPendingSyncConflictStore(dao, ConflictStoreTestGenerationProvider())
            store.write(
                SyncConflictSet(
                    source = SyncBackendType.S3,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/note.md",
                                localContent = "local",
                                remoteContent = "remote",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 123L,
                ),
            )
            store.write(
                SyncConflictSet(
                    source = SyncBackendType.WEBDAV,
                    files =
                        listOf(
                            SyncConflictFile(
                                relativePath = "lomo/memo/other.md",
                                localContent = "left",
                                remoteContent = "right",
                                isBinary = false,
                            ),
                        ),
                    timestamp = 456L,
                ),
            )

            store.clear(SyncBackendType.S3)

            store.readDescriptor(SyncBackendType.S3).shouldBeNull()
            store.readDescriptor(SyncBackendType.WEBDAV)?.source shouldBe SyncBackendType.WEBDAV
        }
}

private class FakePendingSyncConflictDao : PendingSyncConflictDao {
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

    fun payloadFor(source: SyncBackendType): String =
        requireNotNull(entries["workspace-test" to source.name]).payloadJson
}

private class ConflictStoreTestGenerationProvider : WorkspaceSyncGenerationProvider {
    override suspend fun activeGeneration(): WorkspaceSyncGeneration = WorkspaceSyncGeneration("workspace-test")
}

private val testSyncLayout =
    SyncDirectoryLayout(
        memoFolder = "memo",
        imageFolder = "images",
        voiceFolder = "voice",
        allSameDirectory = false,
    )

private val testS3Config =
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

private fun fileVaultRoot(root: File): S3LocalSyncMode.FileVaultRoot =
    S3LocalSyncMode.FileVaultRoot(
        rootDir = root,
        memoRelativeDir = null,
        imageRelativeDir = null,
        voiceRelativeDir = null,
        legacyRemoteCompatibility = false,
    )

private fun fileBridgeScope(root: File): S3SyncFileBridgeScope =
    S3SyncFileBridgeScope(
        runtime = mockk(),
        encodingSupport = S3SyncEncodingSupport(),
        safTreeAccess = UnsupportedS3SafTreeAccess,
        mode = fileVaultRoot(root),
    )

private class PendingStoreProbeS3Client(
    private val expectedKey: String,
    private val remoteContent: ByteArray,
) : LomoS3Client {
    constructor(
        expectedKey: String,
        remoteContent: String,
    ) : this(expectedKey, remoteContent.toByteArray(Charsets.UTF_8))

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
    ): S3RemoteObject = error("Pending store materialization test does not download files")

    override suspend fun getSmallObject(key: String): S3SmallObjectPayload {
        key shouldBe expectedKey
        return S3SmallObjectPayload(
            key = key,
            eTag = "remote-etag",
            lastModified = 20L,
            metadata = emptyMap(),
            bytes = remoteContent,
        )
    }

    override suspend fun putSmallObject(
        key: String,
        bytes: ByteArray,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = error("Pending store materialization test does not upload")

    override suspend fun putObjectFile(
        key: String,
        file: File,
        contentType: String,
        metadata: Map<String, String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): S3PutObjectResult = error("Pending store materialization test does not upload files")

    override suspend fun deleteObject(key: String) {
        error("Pending store materialization test does not delete")
    }

    override fun close() = Unit
}

private fun conflictAction(path: String): S3SyncAction =
    S3SyncAction(
        path = path,
        direction = S3SyncDirection.CONFLICT,
        reason = S3SyncReason.CONFLICT,
    )
