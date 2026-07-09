package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3RemoteListPage
import com.lomo.data.s3.S3RemoteObject
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.testing.DataFunSpec
import com.lomo.data.testing.KotestTemporaryFolder
import com.lomo.data.testing.fakes.FakeS3SafTreeAccess
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: S3SyncFileBridge
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: apply one mode-aware S3 local workspace contract across local listing,
 *   remote normalization, file lookup, read/write/delete, and legacy fallback.
 *
 * Scenarios:
 * - Given an explicit file vault root, when local and remote files are listed, then every path-safe
 *   file under that configured root is in the S3 sync universe, including non-memo binary/config files.
 * - Given an explicit SAF vault root, when local files are listed, then every path-safe SAF file is included.
 * - Given hidden, system, legacy-compatibility, or path-unsafe files, when configured-root mode runs,
 *   then those files are excluded by explicit workspace safety policy.
 * - Given legacy mode with no S3 local sync directory, when local and remote files are listed,
 *   then the old memo/image/voice content filtering still applies.
 * - Given legacy memo storage, when local files are listed, then snapshots are metadata-only with no
 *   fingerprint reads, and the content fingerprint resolves on demand through the storage fingerprint
 *   boundary without reading memo text into a String/ByteArray.
 *
 * Test Change Justification:
 * - Reason category: production contract change (fingerprint laziness).
 * - Old behavior/assertion being replaced: legacy listing eagerly fingerprinted every memo via
 *   fingerprintFileIn and snapshots carried the fingerprint.
 * - Why old assertion is no longer correct: enumeration is metadata-only now; eager per-file
 *   fingerprinting on every snapshot was the systemic hot-path cost being removed.
 * - Coverage preserved by: the same fingerprint value remains reachable through
 *   computeLocalFingerprint using the identical storage boundary, asserted in the rewritten scenario;
 *   planner-side on-demand use is covered by S3LocalFingerprintResolutionTest.
 * - Why this is not fitting the test to the implementation: the observable capability (fingerprint
 *   without text reads) is unchanged; only its resolution moment moved from listing to demand.
 *
 * Observable outcomes:
 * - Local and remote relative-path maps, bridge returned metadata/content/fingerprints, and persisted filesystem state.
 *
 * TDD proof:
 * - RED Report 10 local-sync-directory contract: configured file/SAF root tests fail because safe
 *   non-Lomo files such as docs/spec.pdf and plugins/plugin.json are filtered by isSyncableContentPath.
 * - GREEN Report 10 local-sync-directory contract: configured-root mode accepts all path-safe
 *   non-hidden files while legacy mode still filters to memo/image/voice content.
 *
 * - Excludes: S3 transport implementation details, metadata DAO persistence, planner conflict rules, and UI rendering.
 */
class S3SyncFileBridgeTest : DataFunSpec() {
    init {
        beforeTest {
            tempFolder = KotestTemporaryFolder()
            setUp()
        }

        afterTest {
            tempFolder.cleanup()
        }

        test("localFiles recursively collects content files under inferred vault root") { `localFiles recursively collects content files under inferred vault root`() }

        test("localFiles falls back to legacy lomo layout when roots share only filesystem root") { `localFiles falls back to legacy lomo layout when roots share only filesystem root`() }

        test("localFiles prefer s3 specific sync directory over legacy mode") { `localFiles prefer s3 specific sync directory over legacy mode`() }

        test("configured file vault root treats safe non memo files as workspace content") {
            `configured file vault root treats safe non memo files as workspace content`()
        }

        test("configured SAF vault root treats safe non memo files as workspace content") {
            `configured SAF vault root treats safe non memo files as workspace content`()
        }

        test("remoteFiles keep configured vault relative folders when explicit sync directory is set") { `remoteFiles keep configured vault relative folders when explicit sync directory is set`() }

        test("remoteFiles keeps configured root safe files and ignores hidden or path unsafe objects") {
            `remoteFiles keeps configured root safe files and ignores hidden or path unsafe objects`()
        }

        test("remoteFiles ignore legacy lomo folders for explicit sync directory") { `remoteFiles ignore legacy lomo folders for explicit sync directory`() }

        test("legacy mode keeps memo image voice content filtering") { `legacy mode keeps memo image voice content filtering`() }

        test("legacy local memo listing uses storage fingerprint boundary without reading memo text") {
            `legacy local memo listing uses storage fingerprint boundary without reading memo text`()
        }
    }


    private lateinit var tempFolder: KotestTemporaryFolder
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
    private lateinit var metadataDao: S3SyncMetadataDao

    @MockK(relaxed = true)
    private lateinit var memoSynchronizer: MemoSynchronizer

    @MockK(relaxed = true)
    private lateinit var client: LomoS3Client

    private lateinit var bridge: S3SyncFileBridge

    fun setUp() {
        MockKAnnotations.init(this)
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(),
                stateHolder = S3SyncStateHolder(),
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = NoOpS3SyncTransactionRunner,
            )
        bridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport())
    }

    private fun `localFiles recursively collects content files under inferred vault root`() =
        runTest {
            val vaultRoot = tempFolder.newFolder("vault-root")
            val memoDir = File(vaultRoot, "memo").also(File::mkdirs)
            val imageDir = File(vaultRoot, "attachments/images").also(File::mkdirs)
            val voiceDir = File(vaultRoot, "attachments/voice").also(File::mkdirs)
            File(memoDir, "today.md").writeText("# today")
            File(vaultRoot, "Projects/project.md").apply {
                parentFile?.mkdirs()
                writeText("# nested")
            }
            File(imageDir, "cover.png").writeBytes(byteArrayOf(1, 2, 3))
            File(voiceDir, "clip.m4a").writeBytes(byteArrayOf(4, 5, 6))
            File(vaultRoot, ".obsidian/workspace.json").apply {
                parentFile?.mkdirs()
                writeText("{}")
            }
            File(vaultRoot, ".hidden/secret.md").apply {
                parentFile?.mkdirs()
                writeText("ignore")
            }
            File(vaultRoot, "plugins/plugin.json").apply {
                parentFile?.mkdirs()
                writeText("{}")
            }

            configureRoots(
                memoRoot = memoDir,
                imageRoot = imageDir,
                voiceRoot = voiceDir,
            )

            val files = bridge.localFiles(com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore))

            files.keys shouldBe setOf(
                    "memo/today.md",
                    "Projects/project.md",
                    "attachments/images/cover.png",
                    "attachments/voice/clip.m4a",
                )
        }

    private fun `localFiles falls back to legacy lomo layout when roots share only filesystem root`() =
        runTest {
            configureRoots(
                memoRoot = File("/memo"),
                imageRoot = File("/images"),
                voiceRoot = File("/voice"),
            )
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(FileMetadata(filename = "legacy.md", lastModified = 10L))
            coEvery { localMediaSyncStore.listFiles(any()) } returns
                mapOf(
                    "images/cover.png" to com.lomo.data.webdav.LocalMediaSyncFile("images/cover.png", 20L),
                    "voice/clip.m4a" to com.lomo.data.webdav.LocalMediaSyncFile("voice/clip.m4a", 30L),
                )

            val files = bridge.localFiles(com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore))

            files.keys shouldBe setOf(
                    "lomo/memo/legacy.md",
                    "lomo/images/cover.png",
                    "lomo/voice/clip.m4a",
                )
        }

    private fun `legacy local memo listing uses storage fingerprint boundary without reading memo text`() =
        runTest {
            val content = "# legacy memo".toByteArray(Charsets.UTF_8)
            configureRoots(
                memoRoot = File("/memo"),
                imageRoot = File("/images"),
                voiceRoot = File("/voice"),
            )
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(FileMetadata(filename = "legacy.md", lastModified = 10L, size = content.size.toLong()))
            coEvery { markdownStorageDataSource.fingerprintFileIn(MemoDirectoryType.MAIN, "legacy.md") } returns
                content.md5Hex()
            coEvery { markdownStorageDataSource.readFileIn(MemoDirectoryType.MAIN, "legacy.md") } answers {
                error("legacy local fingerprinting must not read memo text")
            }
            coEvery { localMediaSyncStore.listFiles(any()) } returns emptyMap()
            val layout = SyncDirectoryLayout.resolve(dataStore)

            val files = bridge.localFiles(layout)

            files.getValue("lomo/memo/legacy.md").size shouldBe content.size.toLong()
            files.getValue("lomo/memo/legacy.md").localFingerprint shouldBe null
            coVerify(exactly = 0) { markdownStorageDataSource.fingerprintFileIn(any(), any()) }

            val scope = bridge.modeAware(resolveLocalSyncModeForTest())
            scope.computeLocalFingerprint("lomo/memo/legacy.md", layout) shouldBe content.md5Hex()
        }

    private suspend fun resolveLocalSyncModeForTest(): S3LocalSyncMode {
        val runtime =
            S3SyncRepositoryContext(
                dataStore = dataStore,
                credentialStore = credentialStore,
                clientFactory = clientFactory,
                markdownStorageDataSource = markdownStorageDataSource,
                localMediaSyncStore = localMediaSyncStore,
                metadataDao = metadataDao,
                memoSynchronizer = memoSynchronizer,
                planner = S3SyncPlanner(),
                stateHolder = S3SyncStateHolder(),
                performanceTuner = DisabledSyncPerformanceTuner,
                transactionRunner = NoOpS3SyncTransactionRunner,
            )
        return resolveLocalSyncMode(runtime)
    }

    private fun `localFiles prefer s3 specific sync directory over legacy mode`() =
        runTest {
            val vaultRoot = tempFolder.newFolder("obsidian-vault")
            File(vaultRoot, "Daily/today.md").apply {
                parentFile?.mkdirs()
                writeText("# today")
            }
            File(vaultRoot, "assets/image.png").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(7, 8, 9))
            }
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf("/memo")
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf("/images")
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice")
            every { dataStore.voiceUri } returns flowOf(null)
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(FileMetadata(filename = "legacy.md", lastModified = 10L))
            coEvery { localMediaSyncStore.listFiles(any()) } returns
                mapOf("images/legacy.png" to com.lomo.data.webdav.LocalMediaSyncFile("images/legacy.png", 20L))

            val files = bridge.localFiles(com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore))

            files.keys shouldBe setOf(
                    "Daily/today.md",
                    "assets/image.png",
                )
        }

    private fun `configured file vault root treats safe non memo files as workspace content`() =
        runTest {
            val vaultRoot = tempFolder.newFolder("configured-workspace")
            File(vaultRoot, "docs/spec.pdf").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            File(vaultRoot, "plugins/plugin.json").apply {
                parentFile?.mkdirs()
                writeText("""{"enabled":true}""")
            }
            File(vaultRoot, "notes/today.md").apply {
                parentFile?.mkdirs()
                writeText("# today")
            }
            File(vaultRoot, ".obsidian/workspace.json").apply {
                parentFile?.mkdirs()
                writeText("{}")
            }
            File(vaultRoot, "System Volume Information/index.dat").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(9))
            }
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(File(vaultRoot, "notes").absolutePath)
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf(File(vaultRoot, "media").absolutePath)
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf(File(vaultRoot, "voice").absolutePath)
            every { dataStore.voiceUri } returns flowOf(null)
            val layout = SyncDirectoryLayout.resolve(dataStore)

            val files = bridge.localFiles(layout)
            val directFile = bridge.localFile("docs/spec.pdf", layout)
            val readBytes = bridge.readLocalBytes("docs/spec.pdf", layout)
            bridge.writeLocalBytes("restore/archive.zip", byteArrayOf(7, 8), layout)
            bridge.deleteLocalFile("plugins/plugin.json", layout)

            files.keys shouldBe setOf(
                "docs/spec.pdf",
                "notes/today.md",
                "plugins/plugin.json",
            )
            directFile?.path shouldBe "docs/spec.pdf"
            readBytes?.toList() shouldBe listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte())
            File(vaultRoot, "restore/archive.zip").readBytes().toList() shouldBe listOf(7.toByte(), 8.toByte())
            File(vaultRoot, "plugins/plugin.json").exists() shouldBe false
            File(vaultRoot, ".obsidian/workspace.json").exists() shouldBe true
            File(vaultRoot, "System Volume Information/index.dat").exists() shouldBe true
        }

    private fun `configured SAF vault root treats safe non memo files as workspace content`() =
        runTest {
            val safTreeAccess = FakeS3SafTreeAccess()
            safTreeAccess.addFile("docs/spec.pdf", byteArrayOf(1, 2, 3), lastModified = 10L)
            safTreeAccess.addFile("plugins/plugin.json", """{"enabled":true}""".toByteArray(), lastModified = 20L)
            safTreeAccess.addFile("notes/today.md", "# today".toByteArray(), lastModified = 30L)
            safTreeAccess.addFile(".obsidian/workspace.json", "{}".toByteArray(), lastModified = 40L)
            safTreeAccess.addFile("System Volume Information/index.dat", byteArrayOf(9), lastModified = 50L)
            val mode =
                S3LocalSyncMode.SafVaultRoot(
                    rootUriString = "content://tree/workspace",
                    memoRelativeDir = "notes",
                    imageRelativeDir = "media",
                    voiceRelativeDir = "voice",
                    legacyRemoteCompatibility = false,
                )
            val scope =
                S3SyncFileBridgeScope(
                    runtime = mockk(),
                    encodingSupport = S3SyncEncodingSupport(),
                    safTreeAccess = safTreeAccess,
                    mode = mode,
                )

            val files = scope.localFiles(syncDirectoryLayoutForTest)
            val local = scope.localFile("docs/spec.pdf", syncDirectoryLayoutForTest)
            val bytes = scope.readLocalBytes("docs/spec.pdf", syncDirectoryLayoutForTest)
            scope.writeLocalBytes("restore/archive.zip", byteArrayOf(7, 8), syncDirectoryLayoutForTest)
            scope.deleteLocalFile("plugins/plugin.json", syncDirectoryLayoutForTest)

            files.keys shouldBe setOf(
                "docs/spec.pdf",
                "notes/today.md",
                "plugins/plugin.json",
            )
            local?.path shouldBe "docs/spec.pdf"
            bytes?.toList() shouldBe listOf(1.toByte(), 2.toByte(), 3.toByte())
            safTreeAccess.readBytes("content://tree/workspace", "restore/archive.zip")?.toList() shouldBe
                listOf(7.toByte(), 8.toByte())
            safTreeAccess.getFile("content://tree/workspace", "plugins/plugin.json") shouldBe null
        }

    private fun `remoteFiles keep configured vault relative folders when explicit sync directory is set`() =
        runTest {
            val vaultRoot = tempFolder.newFolder("obsidian-vault-explicit")
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(File(vaultRoot, "journal").absolutePath)
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf(File(vaultRoot, "asset").absolutePath)
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf(File(vaultRoot, "voice").absolutePath)
            every { dataStore.voiceUri } returns flowOf(null)
            stubSinglePageRemoteScan(
                remoteObject("journal/today.md"),
                remoteObject("asset/cover.png"),
                remoteObject("pages.kanban/board.md"),
                remoteObject("archives/2024.md"),
            )

            val files = bridge.remoteFiles(client, com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore), config())

            files.keys shouldBe setOf(
                    "journal/today.md",
                    "asset/cover.png",
                    "pages.kanban/board.md",
                    "archives/2024.md",
                )
        }

    private fun `remoteFiles keeps configured root safe files and ignores hidden or path unsafe objects`() =
        runTest {
            val vaultRoot = tempFolder.newFolder("configured-remote-workspace")
            every { dataStore.s3LocalSyncDirectory } returns flowOf(vaultRoot.absolutePath)
            every { dataStore.rootDirectory } returns flowOf(File(vaultRoot, "memo").absolutePath)
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf(File(vaultRoot, "images").absolutePath)
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf(File(vaultRoot, "voice").absolutePath)
            every { dataStore.voiceUri } returns flowOf(null)
            stubSinglePageRemoteScan(
                remoteObject(".obsidian/workspace.json"),
                remoteObject(".hidden/secret.md"),
                remoteObject("System Volume Information/index.dat"),
                remoteObject("docs/spec.pdf"),
                remoteObject("plugins/plugin.json"),
                remoteObject("Projects/project.md"),
                remoteObject("attachments/images/cover.png"),
                remoteObject("attachments/voice/clip.m4a"),
                remoteObject("../escape.md"),
                remoteObject("bad//gap.md"),
            )

            val files = bridge.remoteFiles(client, com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore), config())

            files.keys shouldBe setOf(
                    "Projects/project.md",
                    "attachments/images/cover.png",
                    "attachments/voice/clip.m4a",
                    "docs/spec.pdf",
                    "plugins/plugin.json",
                )
        }

    private fun `remoteFiles ignore legacy lomo folders for explicit sync directory`() =
        runTest {
            every { dataStore.s3LocalSyncDirectory } returns flowOf("content://tree/primary%3AObsidian")
            every { dataStore.rootDirectory } returns flowOf("/memo")
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf("/images")
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice")
            every { dataStore.voiceUri } returns flowOf(null)
            stubSinglePageRemoteScan(
                remoteObject("lomo/memo/today.md"),
                remoteObject("lomo/images/cover.png"),
                remoteObject("lomo/voice/clip.m4a"),
            )

            val files = bridge.remoteFiles(client, com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore), config())

            files.keys shouldBe emptySet<String>()
        }

    private fun `legacy mode keeps memo image voice content filtering`() =
        runTest {
            configureRoots(
                memoRoot = File("/memo"),
                imageRoot = File("/images"),
                voiceRoot = File("/voice"),
            )
            coEvery { markdownStorageDataSource.listMetadataIn(MemoDirectoryType.MAIN) } returns
                listOf(
                    FileMetadata(filename = "legacy.md", lastModified = 10L),
                    FileMetadata(filename = "draft.txt", lastModified = 11L),
                )
            coEvery { localMediaSyncStore.listFiles(any()) } returns
                mapOf(
                    "images/cover.png" to com.lomo.data.webdav.LocalMediaSyncFile("images/cover.png", 20L),
                )
            stubSinglePageRemoteScan(
                remoteObject("lomo/memo/legacy.md"),
                remoteObject("lomo/memo/draft.txt"),
                remoteObject("lomo/images/cover.png"),
                remoteObject("lomo/images/raw.pdf"),
                remoteObject("docs/spec.pdf"),
            )
            val layout = SyncDirectoryLayout.resolve(dataStore)

            val localFiles = bridge.localFiles(layout)
            val remoteFiles = bridge.remoteFiles(client, layout, config())

            localFiles.keys shouldBe setOf(
                "lomo/memo/legacy.md",
                "lomo/images/cover.png",
            )
            remoteFiles.keys shouldBe setOf(
                "lomo/memo/legacy.md",
                "lomo/images/cover.png",
            )
        }

    private fun configureRoots(
        memoRoot: File,
        imageRoot: File,
        voiceRoot: File,
    ) {
        every { dataStore.rootDirectory } returns flowOf(memoRoot.absolutePath)
        every { dataStore.rootUri } returns flowOf(null)
        every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
        every { dataStore.s3LocalSyncDirectory } returns flowOf(null)
    }

    private fun config() =
        S3ResolvedConfig(
            endpointUrl = "https://s3.example.com",
            region = "us-east-1",
            bucket = "bucket",
            prefix = "",
            accessKeyId = "access",
            secretAccessKey = "secret",
            sessionToken = null,
            pathStyle = S3PathStyle.AUTO,
            encryptionMode = S3EncryptionMode.NONE,
            encryptionPassword = null,
        )

    private fun remoteObject(key: String) =
        S3RemoteObject(
            key = key,
            eTag = "etag",
            lastModified = 1L,
            metadata = emptyMap(),
        )

    private companion object {
        val syncDirectoryLayoutForTest =
            SyncDirectoryLayout(
                memoFolder = "memo",
                imageFolder = "images",
                voiceFolder = "voice",
                allSameDirectory = false,
            )
    }

    private fun stubSinglePageRemoteScan(vararg objects: S3RemoteObject) {
        coEvery { client.listPage(prefix = any(), continuationToken = any(), maxKeys = any()) } answers {
            val prefix = firstArg<String>()
            val continuationToken = secondArg<String?>()
            S3RemoteListPage(
                objects =
                    if (continuationToken == null) {
                        objects.filter { remoteObject -> remoteObject.key.startsWith(prefix) }
                    } else {
                        emptyList()
                    },
                nextContinuationToken = null,
            )
        }
    }
}
