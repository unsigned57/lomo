package com.lomo.data.local.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/*
 * Test Contract:
 * - Unit under test: LomoDataStore delegate stores
 * - Behavior focus: persisted settings contracts, uri-directory exclusivity, blank-value removal, and storage format normalization.
 * - Observable outcomes: DataStore-backed flow values and one-shot reads after update operations.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
 * - Excludes: Android Context wiring, DataStore internal corruption handling, and repository consumers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LomoDataStoreDelegatesTest {
    @Test
    fun `root and media stores prefer uris over old directory values`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val rootStore = RootLocationStoreImpl(dataStore)
            val mediaStore = MediaLocationStoreImpl(dataStore)

            rootStore.updateRootDirectory("/vault/root")
            mediaStore.updateImageDirectory("/vault/images")
            mediaStore.updateVoiceDirectory("/vault/voice")

            assertEquals("/vault/root", rootStore.rootDirectory.first())
            assertEquals("/vault/root", rootStore.getRootDirectoryOnce())
            assertEquals("/vault/images", mediaStore.imageDirectory.first())
            assertEquals("/vault/voice", mediaStore.voiceDirectory.first())

            rootStore.updateRootUri("content://tree/root")
            mediaStore.updateImageUri("content://tree/images")
            mediaStore.updateVoiceUri("content://tree/voice")

            assertEquals("content://tree/root", rootStore.rootUri.first())
            assertNull(rootStore.rootDirectory.first())
            assertEquals("content://tree/root", rootStore.getRootDirectoryOnce())
            assertEquals("content://tree/images", mediaStore.imageUri.first())
            assertNull(mediaStore.imageDirectory.first())
            assertEquals("content://tree/voice", mediaStore.voiceUri.first())
            assertNull(mediaStore.voiceDirectory.first())
        }

    @Test
    fun `storage display and interaction stores persist normalized values`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val storageStore = StorageFormatStoreImpl(dataStore)
            val displayStore = DisplayPreferencesStoreImpl(dataStore)
            val interactionStore = InteractionPreferencesStoreImpl(dataStore)

            storageStore.updateStorageFilenameFormat("unsupported")
            storageStore.updateStorageTimestampFormat("bad")
            displayStore.updateDateFormat("MM/dd/yyyy")
            displayStore.updateTimeFormat("hh:mm a")
            displayStore.updateThemeMode("dark")
            interactionStore.updateHapticFeedbackEnabled(false)
            interactionStore.updateShowInputHints(enabled = false)
            interactionStore.updateDoubleTapEditEnabled(enabled = false)
            interactionStore.updateFreeTextCopyEnabled(enabled = true)
            interactionStore.updateQuickSaveOnBackEnabled(enabled = false)

            assertEquals(StorageFilenameFormats.DEFAULT_PATTERN, storageStore.storageFilenameFormat.first())
            assertEquals(StorageTimestampFormats.DEFAULT_PATTERN, storageStore.storageTimestampFormat.first())
            assertEquals("MM/dd/yyyy", displayStore.dateFormat.first())
            assertEquals("hh:mm a", displayStore.timeFormat.first())
            assertEquals("dark", displayStore.themeMode.first())
            assertFalse(interactionStore.hapticFeedbackEnabled.first())
            assertFalse(interactionStore.showInputHints.first())
            assertFalse(interactionStore.doubleTapEditEnabled.first())
            assertTrue(interactionStore.freeTextCopyEnabled.first())
            assertFalse(interactionStore.quickSaveOnBackEnabled.first())
        }

    @Test
    fun `lan share and app version stores remove blank values and persist toggles`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val lanShareStore = LanSharePreferencesStoreImpl(dataStore)
            val appVersionStore = AppVersionStoreImpl(dataStore)

            lanShareStore.updateLanSharePairingKeyHex("A1B2")
            lanShareStore.updateLanShareE2eEnabled(false)
            lanShareStore.updateLanShareDeviceName("Pixel")
            lanShareStore.updateShareCardShowTime(false)
            lanShareStore.updateShareCardShowBrand(false)
            appVersionStore.updateLastAppVersion("0.9.1")

            assertEquals("A1B2", lanShareStore.lanSharePairingKeyHex.first())
            assertFalse(lanShareStore.lanShareE2eEnabled.first())
            assertEquals("Pixel", lanShareStore.lanShareDeviceName.first())
            assertFalse(lanShareStore.shareCardShowTime.first())
            assertFalse(lanShareStore.shareCardShowBrand.first())
            assertEquals("0.9.1", appVersionStore.getLastAppVersionOnce())

            lanShareStore.updateLanSharePairingKeyHex(" ")
            lanShareStore.updateLanShareDeviceName("")
            appVersionStore.updateLastAppVersion("")

            assertNull(lanShareStore.lanSharePairingKeyHex.first())
            assertNull(lanShareStore.lanShareDeviceName.first())
            assertNull(appVersionStore.getLastAppVersionOnce())
        }

    @Test
    fun `git webdav and draft stores persist configuration and clear empty draft`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val gitBehaviorStore = GitSyncBehaviorStoreImpl(dataStore)
            val gitIdentityStore = GitIdentityStoreImpl(dataStore)
            val gitStatusStore = GitSyncStatusStoreImpl(dataStore)
            val webDavConnectionStore = WebDavConnectionStoreImpl(dataStore)
            val webDavScheduleStore = WebDavScheduleStoreImpl(dataStore)
            val draftStore = DraftStoreImpl(dataStore)

            gitBehaviorStore.updateGitSyncEnabled(true)
            gitBehaviorStore.updateGitAutoSyncEnabled(true)
            gitBehaviorStore.updateGitAutoSyncInterval("30m")
            gitBehaviorStore.updateGitSyncOnRefresh(true)
            gitBehaviorStore.updateSyncBackendType("git")
            gitIdentityStore.updateGitRemoteUrl("https://example.com/repo.git")
            gitIdentityStore.updateGitAuthorName("Lomo")
            gitIdentityStore.updateGitAuthorEmail("lomo@example.com")
            gitStatusStore.updateGitLastSyncTime(1234L)
            webDavConnectionStore.updateWebDavSyncEnabled(true)
            webDavConnectionStore.updateWebDavProvider("custom")
            webDavConnectionStore.updateWebDavBaseUrl("https://dav.example.com")
            webDavConnectionStore.updateWebDavEndpointUrl("https://dav.example.com/notes")
            webDavConnectionStore.updateWebDavUsername("alice")
            webDavScheduleStore.updateWebDavAutoSyncEnabled(true)
            webDavScheduleStore.updateWebDavAutoSyncInterval("2h")
            webDavScheduleStore.updateWebDavLastSyncTime(5678L)
            webDavScheduleStore.updateWebDavSyncOnRefresh(true)
            draftStore.updateDraftText("draft body")

            assertTrue(gitBehaviorStore.gitSyncEnabled.first())
            assertTrue(gitBehaviorStore.gitAutoSyncEnabled.first())
            assertEquals("30m", gitBehaviorStore.gitAutoSyncInterval.first())
            assertTrue(gitBehaviorStore.gitSyncOnRefresh.first())
            assertEquals("git", gitBehaviorStore.syncBackendType.first())
            assertEquals("https://example.com/repo.git", gitIdentityStore.gitRemoteUrl.first())
            assertEquals("Lomo", gitIdentityStore.gitAuthorName.first())
            assertEquals("lomo@example.com", gitIdentityStore.gitAuthorEmail.first())
            assertEquals(1234L, gitStatusStore.gitLastSyncTime.first())
            assertTrue(webDavConnectionStore.webDavSyncEnabled.first())
            assertEquals("custom", webDavConnectionStore.webDavProvider.first())
            assertEquals("https://dav.example.com", webDavConnectionStore.webDavBaseUrl.first())
            assertEquals("https://dav.example.com/notes", webDavConnectionStore.webDavEndpointUrl.first())
            assertEquals("alice", webDavConnectionStore.webDavUsername.first())
            assertTrue(webDavScheduleStore.webDavAutoSyncEnabled.first())
            assertEquals("2h", webDavScheduleStore.webDavAutoSyncInterval.first())
            assertEquals(5678L, webDavScheduleStore.webDavLastSyncTime.first())
            assertTrue(webDavScheduleStore.webDavSyncOnRefresh.first())
            assertEquals("draft body", draftStore.draftText.first())

            gitIdentityStore.updateGitRemoteUrl(" ")
            webDavConnectionStore.updateWebDavBaseUrl(" ")
            webDavConnectionStore.updateWebDavEndpointUrl(null)
            webDavConnectionStore.updateWebDavUsername("")
            draftStore.updateDraftText("")

            assertNull(gitIdentityStore.gitRemoteUrl.first())
            assertNull(webDavConnectionStore.webDavBaseUrl.first())
            assertNull(webDavConnectionStore.webDavEndpointUrl.first())
            assertNull(webDavConnectionStore.webDavUsername.first())
            assertEquals("", draftStore.draftText.first())
        }

    private fun newDataStore(scope: CoroutineScope): androidx.datastore.core.DataStore<Preferences> {
        val backingFile = tempPreferencesFile()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { backingFile },
        )
    }

    private fun tempPreferencesFile(): File =
        Files.createTempFile("lomo-datastore", ".preferences_pb").toFile().apply {
            deleteOnExit()
        }
}
