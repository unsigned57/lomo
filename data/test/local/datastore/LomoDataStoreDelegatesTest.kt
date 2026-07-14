package com.lomo.data.local.datastore


import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lomo.data.util.PreferenceKeys
import com.lomo.domain.model.StorageFilenameFormats
import com.lomo.domain.model.StorageTimestampFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.UUID
import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: LomoDataStore delegate stores
 * - Owning layer: data/local datastore
 * - Priority tier: P1
 * - Capability: persist non-sensitive settings while limiting legacy credential access to one drain-only migration path.
 *
 * Scenarios:
 * - Given legacy root and media directories, when content URIs are set, then URI values take precedence and legacy directory values are cleared.
 * - Given invalid storage formats, when settings are persisted, then storage format defaults are exposed.
 * - Given LAN share preferences, when a device identity is requested repeatedly, then one valid UUID is generated and persisted.
 * - Given legacy pairing material, when the migration store is used, then the credential can only be drained.
 * - Given blank nullable settings, when values are persisted, then nullable settings are removed.
 * - Given sync and draft settings, when stores are updated, then persisted flow values reflect the updates.
 *
 * Observable outcomes:
 * - DataStore-backed flow values, one-shot reads, and drain result after update operations.
 *
 * TDD proof:
 * - RED: before the fix, LanSharePreferencesStoreImpl had no persisted device identity and the lifecycle created a new UUID per process.
 *
 * Excludes:
 * - Android Context wiring, DataStore internal corruption handling, credential repository storage, and repository consumers.
 *
 * Test Change Justification:
 * - Reason category: Data layer module gained app update install persistence, migration archive staging workspace, settings preference repos, and strengthened sync conflict store contracts.
 * - Old behavior/assertion being replaced: previous data layer tests relied on older repository contracts and store implementations before these modules were restructured.
 * - Why old assertion is no longer correct: new modules introduce typed credential reads, positional memo identities, and staged migration/restore plans that change observable data behavior.
 * - Coverage preserved by: all existing repository scenarios retained; new scenarios added for install persistence, staging workspace, preference repos, and conflict store contracts.
 * - Why this is not fitting the test to the implementation: tests verify observable repository store outcomes, not internal implementation details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LomoDataStoreDelegatesTest : DataFunSpec() {
    init {
        test("root and media stores prefer uris over old directory values") { `root and media stores prefer uris over old directory values`() }

        test("storage display and interaction stores persist normalized values") { `storage display and interaction stores persist normalized values`() }

        test("lan share app version and legacy credential stores remove blank values persist toggles and drain pairing key") {
            `lan share app version and legacy credential stores remove blank values persist toggles and drain pairing key`()
        }

        test("lan share device uuid is generated once and reused") { `lan share device uuid is generated once and reused`() }

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

    private fun `lan share app version and legacy credential stores remove blank values persist toggles and drain pairing key`() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val lanShareStore = LanSharePreferencesStoreImpl(dataStore)
            val legacyCredentialStore = LegacyCredentialDrainStoreImpl(dataStore)
            val appVersionStore = AppVersionStoreImpl(dataStore)

            dataStore.edit {
                it[stringPreferencesKey(PreferenceKeys.LAN_SHARE_PAIRING_KEY_HEX)] = "A1B2"
            }
            lanShareStore.updateLanShareE2eEnabled(false)
            lanShareStore.updateLanShareDeviceName("Pixel")
            lanShareStore.updateShareCardShowTime(false)
            lanShareStore.updateShareCardShowBrand(false)
            appVersionStore.updateLastAppVersion("0.9.1")

            legacyCredentialStore.drainLegacyLanSharePairingKeyHex() shouldBe "A1B2"
            legacyCredentialStore.drainLegacyLanSharePairingKeyHex().shouldBeNull()
            (lanShareStore.lanShareE2eEnabled.first()).shouldBeFalse()
            lanShareStore.lanShareDeviceName.first() shouldBe "Pixel"
            (lanShareStore.shareCardShowTime.first()).shouldBeFalse()
            (lanShareStore.shareCardShowBrand.first()).shouldBeFalse()
            appVersionStore.getLastAppVersionOnce() shouldBe "0.9.1"

            lanShareStore.updateLanShareDeviceName("")
            appVersionStore.updateLastAppVersion("")

            lanShareStore.lanShareDeviceName.first().shouldBeNull()
            appVersionStore.getLastAppVersionOnce().shouldBeNull()
        }

    private fun `lan share device uuid is generated once and reused`() =
        runTest {
            val store = LanSharePreferencesStoreImpl(newDataStore(backgroundScope))

            val first = store.getOrCreateLanShareDeviceUuid()
            val second = store.getOrCreateLanShareDeviceUuid()

            UUID.fromString(first).toString() shouldBe first
            second shouldBe first
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
