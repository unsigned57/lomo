package com.lomo.data.repository

import com.lomo.data.local.dao.S3SyncMetadataDao
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.s3.LomoS3Client
import com.lomo.data.s3.LomoS3ClientFactory
import com.lomo.data.s3.S3CredentialStore
import com.lomo.data.source.MarkdownStorageDataSource
import com.lomo.data.source.FileMetadata
import com.lomo.data.source.MemoDirectoryType
import com.lomo.data.webdav.LocalMediaSyncStore
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/*
 * Test Contract:
 * - Unit under test: S3SyncFileBridge
 * - Behavior focus: vault-root mode recursively collects syncable content files under the inferred local sync root and filters remote listings to the same content-only contract.
 * - Observable outcomes: local and remote relative-path maps preserve nested hierarchy, include markdown and supported attachments, and ignore hidden or unsupported external files.
 * - Red phase: Fails before the fix because S3 sync still assumes the legacy three-directory lomo/ model instead of recursive vault-root content syncing.
 * - Excludes: S3 transport implementation details, metadata DAO persistence, planner conflict rules, and UI rendering.
 */
class S3SyncFileBridgeTest {
    @get:Rule val tempFolder = TemporaryFolder()

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

    @Before
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
            )
        bridge = S3SyncFileBridge(runtime, S3SyncEncodingSupport())
    }

    @Test
    fun `localFiles recursively collects content files under inferred vault root`() =
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

            assertEquals(
                setOf(
                    "memo/today.md",
                    "Projects/project.md",
                    "attachments/images/cover.png",
                    "attachments/voice/clip.m4a",
                ),
                files.keys,
            )
        }

    @Test
    fun `localFiles falls back to legacy lomo layout when roots share only filesystem root`() =
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

            assertEquals(
                setOf(
                    "lomo/memo/legacy.md",
                    "lomo/images/cover.png",
                    "lomo/voice/clip.m4a",
                ),
                files.keys,
            )
        }

    @Test
    fun `localFiles prefer s3 specific sync directory over legacy mode`() =
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

            assertEquals(
                setOf(
                    "Daily/today.md",
                    "assets/image.png",
                ),
                files.keys,
            )
        }

    @Test
    fun `remoteFiles keeps nested content files and ignores hidden or unsupported objects`() =
        runTest {
            configureRoots(
                memoRoot = tempFolder.newFolder("memo-root"),
                imageRoot = tempFolder.newFolder("image-root"),
                voiceRoot = tempFolder.newFolder("voice-root"),
            )
            coEvery { client.list(prefix = "", maxKeys = null) } returns
                listOf(
                    remoteObject(".obsidian/workspace.json"),
                    remoteObject(".hidden/secret.md"),
                    remoteObject("plugins/plugin.json"),
                    remoteObject("Projects/project.md"),
                    remoteObject("attachments/images/cover.png"),
                    remoteObject("attachments/voice/clip.m4a"),
                )

            val files = bridge.remoteFiles(client, com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore), config())

            assertEquals(
                setOf(
                    "Projects/project.md",
                    "attachments/images/cover.png",
                    "attachments/voice/clip.m4a",
                ),
                files.keys,
            )
        }

    @Test
    fun `remoteFiles remap legacy lomo folders for saf specific sync directory`() =
        runTest {
            every { dataStore.s3LocalSyncDirectory } returns flowOf("content://tree/primary%3AObsidian")
            every { dataStore.rootDirectory } returns flowOf("/memo")
            every { dataStore.rootUri } returns flowOf(null)
            every { dataStore.imageDirectory } returns flowOf("/images")
            every { dataStore.imageUri } returns flowOf(null)
            every { dataStore.voiceDirectory } returns flowOf("/voice")
            every { dataStore.voiceUri } returns flowOf(null)
            coEvery { client.list(prefix = "", maxKeys = null) } returns
                listOf(
                    remoteObject("lomo/memo/today.md"),
                    remoteObject("lomo/images/cover.png"),
                    remoteObject("lomo/voice/clip.m4a"),
                )

            val files = bridge.remoteFiles(client, com.lomo.data.sync.SyncDirectoryLayout.resolve(dataStore), config())

            assertEquals(
                setOf(
                    "today.md",
                    "cover.png",
                    "clip.m4a",
                ),
                files.keys,
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
        com.lomo.data.s3.S3RemoteObject(
            key = key,
            eTag = "etag",
            lastModified = 1L,
            metadata = emptyMap(),
        )
}
