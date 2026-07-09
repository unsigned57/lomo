/*
 * Behavior Contract:
 * - Unit under test: GitMediaSyncBridge
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: Git media reconcile copies, deletes, and unchanged decisions are driven by the data-layer media fingerprint index.
 *
 * Scenarios:
 * - Given local-only media, when reconcile runs, then the media is streamed into the Git repo and indexed.
 * - Given repo-only media, when reconcile runs, then the media is streamed into the local media root and indexed.
 * - Given a previously indexed media file is missing on one side, when reconcile runs, then the unchanged surviving copy is deleted.
 * - Given local and repo media fingerprints already match, when reconcile runs, then no full-byte read/compare is performed.
 *
 * Observable outcomes:
 * - Repo/local file existence and contents, GitMediaSyncSummary change flags, persisted metadata entries, and forbidden byte-read calls.
 *
 * TDD proof:
 * - RED: `./kotlin test --include-classes='com.lomo.data.git.GitMediaSyncBridgeTest'`
 *   fails before the fix because the bridge has no fingerprint/index owner and still routes writes through readBytes/contentEquals.
 *
 * Excludes:
 * - Git transport, SAF provider behavior, and workspace media descriptor contracts.
 */

package com.lomo.data.git

import android.content.Context
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.SyncDirectoryLayout
import com.lomo.data.webdav.LocalMediaSyncFile
import com.lomo.data.webdav.LocalMediaSyncStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse

class GitMediaSyncBridgeTest : DataFunSpec() {
    init {
        test("reconcile copies local independent image into repo images folder") { `reconcile copies local independent image into repo images folder`() }

        test("reconcile copies repo voice file into local independent voice root") { `reconcile copies repo voice file into local independent voice root`() }

        test("reconcile deletes repo image when local file was removed after prior sync") { `reconcile deletes repo image when local file was removed after prior sync`() }

        test("reconcile deletes local voice when repo file was removed after prior sync") { `reconcile deletes local voice when repo file was removed after prior sync`() }

        test("reconcile skips byte reads when local and repo fingerprints match") { `reconcile skips byte reads when local and repo fingerprints match`() }
    }


    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<LomoDataStore>(relaxed = true)

    private val defaultLayout =
        SyncDirectoryLayout(
            memoFolder = "memo",
            imageFolder = "images",
            voiceFolder = "voice",
            allSameDirectory = false,
        )

    private fun `reconcile copies local independent image into repo images folder`() =
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
            (repoFile.exists()).shouldBeTrue()
            repoFile.readText() shouldBe "local-image"
            (result.repoChanged).shouldBeTrue()
            (stateStore.read().containsKey("images/img_1.jpg")).shouldBeTrue()
        }

    private fun `reconcile copies repo voice file into local independent voice root`() =
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
            (localFile.exists()).shouldBeTrue()
            localFile.readText() shouldBe "remote-audio"
            (result.repoChanged).shouldBeFalse()
            (result.localChanged).shouldBeTrue()
            (stateStore.read().containsKey("voice/voice_1.m4a")).shouldBeTrue()
        }

    private fun `reconcile deletes repo image when local file was removed after prior sync`() =
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

            (result.repoChanged).shouldBeTrue()
            (result.localChanged).shouldBeFalse()
            (repoFile.exists()).shouldBeFalse()
            (stateStore.read().isEmpty()).shouldBeTrue()
        }

    private fun `reconcile deletes local voice when repo file was removed after prior sync`() =
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

            (result.repoChanged).shouldBeFalse()
            (result.localChanged).shouldBeTrue()
            (localFile.exists()).shouldBeFalse()
            (stateStore.read().isEmpty()).shouldBeTrue()
        }

    private fun `reconcile skips byte reads when local and repo fingerprints match`() =
        runTest {
            val repoRoot = Files.createTempDirectory("lomo-media-repo").toFile()
            val repoDir = File(repoRoot, "images").apply { mkdirs() }
            val repoFile = File(repoDir, "img_1.jpg")
            repoFile.writeText("same-image")
            repoFile.setLastModified(30_000L)
            val fingerprint = "1327ca08d4f2cbd52048bd0242b31ab4"
            val localMediaSyncStore: LocalMediaSyncStore = mockk()
            val stateStore =
                InMemoryGitMediaSyncStateStore(
                    listOf(
                        GitMediaSyncMetadataEntry(
                            relativePath = "images/img_1.jpg",
                            repoLastModified = 10_000L,
                            localLastModified = 10_000L,
                            repoSize = 10L,
                            localSize = 10L,
                            repoFingerprint = fingerprint,
                            localFingerprint = fingerprint,
                            lastSyncedAt = 10_000L,
                            lastResolvedDirection = GitMediaSyncMetadataEntry.UNCHANGED,
                            lastResolvedReason = GitMediaSyncMetadataEntry.UNCHANGED,
                        ),
                    ),
                )
            coEvery { localMediaSyncStore.configuredCategories() } returns setOf(com.lomo.data.webdav.MediaSyncCategory.IMAGE)
            coEvery { localMediaSyncStore.listFiles(defaultLayout) } returns
                mapOf(
                    "images/img_1.jpg" to
                        LocalMediaSyncFile(
                            relativePath = "images/img_1.jpg",
                            lastModified = 20_000L,
                            size = 10L,
                        ),
                )
            coEvery { localMediaSyncStore.md5Hex("images/img_1.jpg", defaultLayout) } returns fingerprint
            coEvery { localMediaSyncStore.readBytes(any(), any()) } throws AssertionError(
                "unchanged fingerprint reconcile must not read full local bytes",
            )
            val bridge =
                GitMediaSyncBridge(
                    localMediaSyncStore = localMediaSyncStore,
                    stateStore = stateStore,
                    planner = GitMediaSyncPlanner(),
                    fingerprintIndex = GitMediaSyncFingerprintIndex(localMediaSyncStore),
                )

            val result = bridge.reconcile(repoRoot, defaultLayout)

            result shouldBe GitMediaSyncSummary()
            coVerify(exactly = 0) { localMediaSyncStore.readBytes(any(), any()) }
        }

    private fun createBridge(stateStore: GitMediaSyncStateStore): GitMediaSyncBridge =
        GitMediaSyncBridge(
            localMediaSyncStore = LocalMediaSyncStore(context, dataStore),
            stateStore = stateStore,
            planner = GitMediaSyncPlanner(),
            fingerprintIndex = GitMediaSyncFingerprintIndex(LocalMediaSyncStore(context, dataStore)),
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

    override suspend fun clear() {
        state = emptyMap()
    }
}
