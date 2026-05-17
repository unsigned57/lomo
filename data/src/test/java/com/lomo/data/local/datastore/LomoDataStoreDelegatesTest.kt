package com.lomo.data.local.datastore


import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Test Contract:
 * - Unit under test: LomoDataStore delegate stores
 * - Behavior focus: persisted settings contracts, uri-directory exclusivity, blank-value removal, and storage format normalization.
 * - Observable outcomes: DataStore-backed flow values and one-shot reads after update operations.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: Android Context wiring, DataStore internal corruption handling, and repository consumers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LomoDataStoreDelegatesTest : DataFunSpec() {
    init {
        test("root and media stores prefer uris over old directory values") { `root and media stores prefer uris over old directory values`() }

        test("storage display and interaction stores persist normalized values") { `storage display and interaction stores persist normalized values`() }

        test("lan share and app version stores remove blank values and persist toggles") { `lan share and app version stores remove blank values and persist toggles`() }

        test("git webdav and draft stores persist configuration and clear empty draft") { `git webdav and draft stores persist configuration and clear empty draft`() }
    }


    private fun `root and media stores prefer uris over old directory values`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val rootStore = RootLocationStoreImpl(dataStore)
            val mediaStore = MediaLocationStoreImpl(dataStore)

            rootStore.updateRootDirectory("/vault/root")
            mediaStore.updateImageDirectory("/vault/images")
            mediaStore.updateVoiceDirectory("/vault/voice")

            rootStore.rootDirectory.first() shouldBe "/vault/root"
            rootStore.getRootDirectoryOnce() shouldBe "/vault/root"
            mediaStore.imageDirectory.first() shouldBe "/vault/images"
            mediaStore.voiceDirectory.first() shouldBe "/vault/voice"

            rootStore.updateRootUri("content://tree/root")
            mediaStore.updateImageUri("content://tree/images")
            mediaStore.updateVoiceUri("content://tree/voice")

            rootStore.rootUri.first() shouldBe "content://tree/root"
            rootStore.rootDirectory.first().shouldBeNull()
            rootStore.getRootDirectoryOnce() shouldBe "content://tree/root"
            mediaStore.imageUri.first() shouldBe "content://tree/images"
            mediaStore.imageDirectory.first().shouldBeNull()
            mediaStore.voiceUri.first() shouldBe "content://tree/voice"
            mediaStore.voiceDirectory.first().shouldBeNull()
        }

    private fun `storage display and interaction stores persist normalized values`() =
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

            storageStore.storageFilenameFormat.first() shouldBe StorageFilenameFormats.DEFAULT_PATTERN
            storageStore.storageTimestampFormat.first() shouldBe StorageTimestampFormats.DEFAULT_PATTERN
            displayStore.dateFormat.first() shouldBe "MM/dd/yyyy"
            displayStore.timeFormat.first() shouldBe "hh:mm a"
            displayStore.themeMode.first() shouldBe "dark"
            (interactionStore.hapticFeedbackEnabled.first()).shouldBeFalse()
            (interactionStore.showInputHints.first()).shouldBeFalse()
            (interactionStore.doubleTapEditEnabled.first()).shouldBeFalse()
            (interactionStore.freeTextCopyEnabled.first()).shouldBeTrue()
            (interactionStore.quickSaveOnBackEnabled.first()).shouldBeFalse()
        }

    private fun `lan share and app version stores remove blank values and persist toggles`() =
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

            lanShareStore.lanSharePairingKeyHex.first() shouldBe "A1B2"
            (lanShareStore.lanShareE2eEnabled.first()).shouldBeFalse()
            lanShareStore.lanShareDeviceName.first() shouldBe "Pixel"
            (lanShareStore.shareCardShowTime.first()).shouldBeFalse()
            (lanShareStore.shareCardShowBrand.first()).shouldBeFalse()
            appVersionStore.getLastAppVersionOnce() shouldBe "0.9.1"

            lanShareStore.updateLanSharePairingKeyHex(" ")
            lanShareStore.updateLanShareDeviceName("")
            appVersionStore.updateLastAppVersion("")

            lanShareStore.lanSharePairingKeyHex.first().shouldBeNull()
            lanShareStore.lanShareDeviceName.first().shouldBeNull()
            appVersionStore.getLastAppVersionOnce().shouldBeNull()
        }

    private fun `git webdav and draft stores persist configuration and clear empty draft`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val gitBehaviorStore = GitSyncBehaviorStoreImpl(dataStore)
            val gitIdentityStore = GitIdentityStoreImpl(dataStore)
            val gitStatusStore = GitSyncStatusStoreImpl(dataStore)
            val webDavConnectionStore = WebDavConnectionStoreImpl(dataStore)
            val webDavScheduleStore = WebDavScheduleStoreImpl(dataStore)
            val s3ConnectionStore = S3ConnectionStoreImpl(dataStore)
            val s3ScheduleStore = S3ScheduleStoreImpl(dataStore)
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
            s3ConnectionStore.updateS3SyncEnabled(true)
            s3ConnectionStore.updateS3EndpointUrl("https://s3.example.com")
            s3ConnectionStore.updateS3Bucket("vault")
            s3ConnectionStore.updateS3LocalSyncDirectory("content://tree/primary%3AObsidian")
            s3ScheduleStore.updateS3AutoSyncEnabled(true)
            s3ScheduleStore.updateS3AutoSyncInterval("6h")
            s3ScheduleStore.updateS3LastSyncTime(6789L)
            s3ScheduleStore.updateS3SyncOnRefresh(true)
            draftStore.updateDraftText("draft body")

            (gitBehaviorStore.gitSyncEnabled.first()).shouldBeTrue()
            (gitBehaviorStore.gitAutoSyncEnabled.first()).shouldBeTrue()
            gitBehaviorStore.gitAutoSyncInterval.first() shouldBe "30m"
            (gitBehaviorStore.gitSyncOnRefresh.first()).shouldBeTrue()
            gitBehaviorStore.syncBackendType.first() shouldBe "git"
            gitIdentityStore.gitRemoteUrl.first() shouldBe "https://example.com/repo.git"
            gitIdentityStore.gitAuthorName.first() shouldBe "Lomo"
            gitIdentityStore.gitAuthorEmail.first() shouldBe "lomo@example.com"
            gitStatusStore.gitLastSyncTime.first() shouldBe 1234L
            (webDavConnectionStore.webDavSyncEnabled.first()).shouldBeTrue()
            webDavConnectionStore.webDavProvider.first() shouldBe "custom"
            webDavConnectionStore.webDavBaseUrl.first() shouldBe "https://dav.example.com"
            webDavConnectionStore.webDavEndpointUrl.first() shouldBe "https://dav.example.com/notes"
            webDavConnectionStore.webDavUsername.first() shouldBe "alice"
            (webDavScheduleStore.webDavAutoSyncEnabled.first()).shouldBeTrue()
            webDavScheduleStore.webDavAutoSyncInterval.first() shouldBe "2h"
            webDavScheduleStore.webDavLastSyncTime.first() shouldBe 5678L
            (webDavScheduleStore.webDavSyncOnRefresh.first()).shouldBeTrue()
            (s3ConnectionStore.s3SyncEnabled.first()).shouldBeTrue()
            s3ConnectionStore.s3EndpointUrl.first() shouldBe "https://s3.example.com"
            s3ConnectionStore.s3Bucket.first() shouldBe "vault"
            s3ConnectionStore.s3LocalSyncDirectory.first() shouldBe "content://tree/primary%3AObsidian"
            (s3ScheduleStore.s3AutoSyncEnabled.first()).shouldBeTrue()
            s3ScheduleStore.s3AutoSyncInterval.first() shouldBe "6h"
            s3ScheduleStore.s3LastSyncTime.first() shouldBe 6789L
            (s3ScheduleStore.s3SyncOnRefresh.first()).shouldBeTrue()
            draftStore.draftText.first() shouldBe "draft body"

            gitIdentityStore.updateGitRemoteUrl(" ")
            webDavConnectionStore.updateWebDavBaseUrl(" ")
            webDavConnectionStore.updateWebDavEndpointUrl(null)
            webDavConnectionStore.updateWebDavUsername("")
            s3ConnectionStore.updateS3LocalSyncDirectory(" ")
            draftStore.updateDraftText("")

            gitIdentityStore.gitRemoteUrl.first().shouldBeNull()
            webDavConnectionStore.webDavBaseUrl.first().shouldBeNull()
            webDavConnectionStore.webDavEndpointUrl.first().shouldBeNull()
            webDavConnectionStore.webDavUsername.first().shouldBeNull()
            s3ConnectionStore.s3LocalSyncDirectory.first().shouldBeNull()
            draftStore.draftText.first() shouldBe ""
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
