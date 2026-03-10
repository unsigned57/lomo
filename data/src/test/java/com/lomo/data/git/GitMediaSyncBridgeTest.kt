package com.lomo.data.git

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitMediaSyncBridgeTest {
    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)

    private val defaultLayout =
        SyncDirectoryLayout(
            memoFolder = "memo",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    @Test
    fun `reconcile copies local independent image into repo images folder`() =
        runTest {
            val mediaRoot = Files.createTempDirectory("lomo-media-local").toFile()
            val repoRoot = Files.createTempDirectory("lomo-media-repo").toFile()
            val imageFile = File(mediaRoot, "img_1.jpg")
            imageFile.writeText("local-image")
            imageFile.setLastModified(2_000L)
            configureImageRoot(mediaRoot)

            val stateStore = InMemoryGitMediaSyncStateStore()
            val bridge = createBridge(stateStore)

            val result = bridge.reconcile(repoRoot, defaultLayout)

            val repoFile = File(repoRoot, "images/img_1.jpg")
            assertTrue(repoFile.exists())
            assertEquals("local-image", repoFile.readText())
            assertTrue(result.repoChanged)
            assertTrue(stateStore.read().containsKey("images/img_1.jpg"))
        }

    @Test
    fun `reconcile copies repo voice file into local independent voice root`() =
        runTest {
            val voiceRoot = Files.createTempDirectory("lomo-voice-local").toFile()
            val repoRoot = Files.createTempDirectory("lomo-voice-repo").toFile()
            val repoDir = File(repoRoot, "voice").apply { mkdirs() }
            val repoFile = File(repoDir, "voice_1.m4a")
            repoFile.writeText("remote-audio")
            repoFile.setLastModified(3_000L)
            configureVoiceRoot(voiceRoot)

            val stateStore = InMemoryGitMediaSyncStateStore()
            val bridge = createBridge(stateStore)

            val result = bridge.reconcile(repoRoot, defaultLayout)

            val localFile = File(voiceRoot, "voice_1.m4a")
            assertTrue(localFile.exists())
            assertEquals("remote-audio", localFile.readText())
            assertFalse(result.repoChanged)
            assertTrue(result.localChanged)
            assertTrue(stateStore.read().containsKey("voice/voice_1.m4a"))
        }

    @Test
    fun `reconcile deletes repo image when local file was removed after prior sync`() =
        runTest {
            val mediaRoot = Files.createTempDirectory("lomo-media-local").toFile()
            val repoRoot = Files.createTempDirectory("lomo-media-repo").toFile()
            val repoDir = File(repoRoot, "images").apply { mkdirs() }
            val repoFile = File(repoDir, "img_1.jpg")
            repoFile.writeText("tracked-image")
            repoFile.setLastModified(4_000L)
            configureImageRoot(mediaRoot)

            val stateStore =
                InMemoryGitMediaSyncStateStore(
                    listOf(
                        GitMediaSyncMetadataEntry(
                            relativePath = "images/img_1.jpg",
                            repoLastModified = 4_000L,
                            localLastModified = 4_000L,
                            lastSyncedAt = 5_000L,
                            lastResolvedDirection = GitMediaSyncDirection.PUSH_TO_REPO.name,
                            lastResolvedReason = GitMediaSyncReason.LOCAL_ONLY.name,
                        ),
                    ),
                )
            val bridge = createBridge(stateStore)

            val result = bridge.reconcile(repoRoot, defaultLayout)

            assertTrue(result.repoChanged)
            assertFalse(result.localChanged)
            assertFalse(repoFile.exists())
            assertTrue(stateStore.read().isEmpty())
        }

    @Test
    fun `reconcile deletes local voice when repo file was removed after prior sync`() =
        runTest {
            val voiceRoot = Files.createTempDirectory("lomo-voice-local").toFile()
            val localFile = File(voiceRoot, "voice_1.m4a")
            localFile.writeText("tracked-audio")
            localFile.setLastModified(4_000L)
            val repoRoot = Files.createTempDirectory("lomo-voice-repo").toFile()
            configureVoiceRoot(voiceRoot)

            val stateStore =
                InMemoryGitMediaSyncStateStore(
                    listOf(
                        GitMediaSyncMetadataEntry(
                            relativePath = "voice/voice_1.m4a",
                            repoLastModified = 4_000L,
                            localLastModified = 4_000L,
                            lastSyncedAt = 5_000L,
                            lastResolvedDirection = GitMediaSyncDirection.PULL_TO_LOCAL.name,
                            lastResolvedReason = GitMediaSyncReason.REPO_ONLY.name,
                        ),
                    ),
                )
            val bridge = createBridge(stateStore)

            val result = bridge.reconcile(repoRoot, defaultLayout)

            assertFalse(result.repoChanged)
            assertTrue(result.localChanged)
            assertFalse(localFile.exists())
            assertTrue(stateStore.read().isEmpty())
        }

    private fun createBridge(stateStore: GitMediaSyncStateStore): GitMediaSyncBridge =
        GitMediaSyncBridge(
            localMediaSyncStore = LocalMediaSyncStore(context, dataStore),
            stateStore = stateStore,
            planner = GitMediaSyncPlanner(),
        )

    private fun configureImageRoot(imageRoot: File) {
        every { dataStore.imageDirectory } returns flowOf(imageRoot.absolutePath)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(null)
        every { dataStore.voiceUri } returns flowOf(null)
    }

    private fun configureVoiceRoot(voiceRoot: File) {
        every { dataStore.imageDirectory } returns flowOf(null)
        every { dataStore.imageUri } returns flowOf(null)
        every { dataStore.voiceDirectory } returns flowOf(voiceRoot.absolutePath)
        every { dataStore.voiceUri } returns flowOf(null)
    }
}

private class InMemoryGitMediaSyncStateStore(
    entries: List<GitMediaSyncMetadataEntry> = emptyList(),
) : GitMediaSyncStateStore {
    private var state = entries.associateBy { it.relativePath }

    override suspend fun read(): Map<String, GitMediaSyncMetadataEntry> = state

    override suspend fun write(entries: Collection<GitMediaSyncMetadataEntry>) {
        state = entries.associateBy { it.relativePath }
    }
}
