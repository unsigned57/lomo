package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineReadiness
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.ProjectionFreshness
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.StorageArea
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import com.lomo.domain.model.WorkspaceAuthority
import com.lomo.domain.model.WorkspaceMount
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/*
 * Behavior Contract:
 * - Unit under test: non-app-config Settings*FeatureViewModel wrapper classes in SettingsFeatureViewModels.kt
 * - Owning layer: app/settings
 * - Priority tier: P2
 * - Capability: expose LAN actions directly while routing Git/WebDAV/S3 common provider actions
 *   through one provider-keyed wrapper plus provider-specific extension actions.
 *
 * Scenarios:
 * - Given a selected root, when the settings scope is cancelled before queued work dispatches,
 *   then the switch owner has already been entered and its durable commit still completes.
 *   TDD: root-navigation-red.log observes committed=false; GREEN uses the same scheduler gate.
 * - Given LAN feature actions, when wrapper methods are invoked, then LAN actions and support hooks forward directly.
 * - Given Git/WebDAV/S3 common provider actions, when wrapper provider methods are invoked,
 *   then one shared provider-keyed action path receives the provider identity.
 * - Given provider-specific Git/WebDAV/S3 fields, when wrapper extension methods are invoked,
 *   then only provider-specific action interfaces receive those values.
 * - Given initial projection Revalidating or Recovery, when settings observes root status, then it
 *   exposes Indexing or Failed without inventing a second scanning state.
 * - Given a memo root switch still in flight, when image and voice directories are picked, then each
 *   area is exposed as pending until its own queued command finishes, so the row never reads "Not set".
 *   TDD proof: target/lomo/reports/manual/directory-selection/media-pending-red.log expected
 *   [IMAGE, VOICE] but was []; media-pending-green.log passes the same scenario.
 *
 * Observable outcomes:
 * - Captured provider-keyed action arguments, provider-specific action arguments, validation helper results,
 *   and coordinator-owned connection-test reset counters.
 *
 * TDD proof:
 * - RED: focused provider shared surface test failed before the fix because wrappers still exposed
 *   duplicated provider-local common action bags.
 *
 * Excludes:
 * - Production DataStore internals, native index execution and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFeatureViewModelsTest : AppFunSpec() {
    init {
        test("given a picked root when settings closes immediately then the admitted switch still commits") {
            runTest {
                val coordinator = mockk<SettingsAppConfigCoordinator>()
                val gate = CompletableDeferred<Unit>()
                val committed = CompletableDeferred<String>()
                coEvery { coordinator.updateRootUri(any()) } coAnswers {
                    val root = firstArg<String>()
                    // The production switch owner becomes uncancellable once entered.
                    withContext(NonCancellable) {
                        gate.await()
                        committed.complete(root)
                    }
                }
                val settingsScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
                val viewModel = SettingsStorageFeatureViewModel(
                    scope = settingsScope,
                    appConfigCoordinator = coordinator,
                    mount = MutableStateFlow(verifiedStorageMount()),
                    onError = { throw it },
                )
                viewModel.updateRootUri("content://tree/empty-root")
                settingsScope.cancel()
                gate.complete(Unit)
                runCurrent()
                committed.isCompleted shouldBe true
                committed.await() shouldBe "content://tree/empty-root"
            }
        }
        test(
            "given a root switch in flight when media directories are picked then each area stays pending until committed",
        ) {
            runTest {
                val coordinator = mockk<SettingsAppConfigCoordinator>()
                val rootGate = CompletableDeferred<Unit>()
                val imageGate = CompletableDeferred<Unit>()
                val voiceGate = CompletableDeferred<Unit>()
                coEvery { coordinator.updateRootUri(any()) } coAnswers { rootGate.await() }
                coEvery { coordinator.updateImageUri(any()) } coAnswers { imageGate.await() }
                coEvery { coordinator.updateVoiceUri(any()) } coAnswers { voiceGate.await() }
                val viewModel = SettingsStorageFeatureViewModel(
                    scope = backgroundScope,
                    appConfigCoordinator = coordinator,
                    mount = MutableStateFlow(verifiedStorageMount()),
                    onError = { throw it },
                )
                backgroundScope.launch { viewModel.pendingMediaAreas.collect {} }

                viewModel.updateRootUri("content://tree/memos")
                viewModel.updateImageUri("content://tree/images")
                viewModel.updateVoiceUri("content://tree/voice")
                runCurrent()
                viewModel.pendingMediaAreas.value shouldBe setOf(StorageArea.IMAGE, StorageArea.VOICE)

                rootGate.complete(Unit)
                imageGate.complete(Unit)
                runCurrent()
                viewModel.pendingMediaAreas.value shouldBe setOf(StorageArea.VOICE)

                voiceGate.complete(Unit)
                runCurrent()
                viewModel.pendingMediaAreas.value shouldBe emptySet()
            }
        }
        test("lan-share feature viewmodel forwards actions to delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()

            val viewModel = SettingsLanShareFeatureViewModel(actionCoordinator)
            viewModel.updateLanShareEnabled(false)
            viewModel.updateLanShareDeviceName("Pixel")

            (actionCoordinator.lanShareEnabledArg) shouldBe (false)
            (actionCoordinator.lanShareDeviceNameArg) shouldBe ("Pixel")
        }

        test("storage feature viewmodel surfaces an asynchronous root switch failure") {
            runTest {
                val failure = IllegalStateException("workspace lock unavailable")
                val coordinator = mockk<SettingsAppConfigCoordinator>()
                coEvery { coordinator.updateRootUri(any()) } throws failure
                val errors = mutableListOf<Throwable>()
                val viewModel =
                    SettingsStorageFeatureViewModel(
                        scope = backgroundScope,
                        appConfigCoordinator = coordinator,
                        mount = MutableStateFlow(verifiedStorageMount()),
                        onError = errors::add,
                    )

                viewModel.updateRootUri("content://tree/root")
                runCurrent()

                errors shouldBe listOf(failure)
            }
        }

        test("storage feature derives indexing and failure from managed mount state") {
            runTest {
                val coordinator = mockk<SettingsAppConfigCoordinator>()
                val mount = MutableStateFlow(verifiedStorageMount())
                val viewModel =
                    SettingsStorageFeatureViewModel(
                        scope = backgroundScope,
                        appConfigCoordinator = coordinator,
                        mount = mount,
                        onError = {},
                    )
                backgroundScope.launch { viewModel.rootOperationState.collect {} }
                runCurrent()

                mount.value =
                    verifiedStorageMount(freshness = ProjectionFreshness.Revalidating(1uL))
                runCurrent()
                viewModel.rootOperationState.value shouldBe WorkspaceRootOperationState.Indexing

                mount.value =
                    WorkspaceMount(
                        readiness =
                        EngineReadiness.ReadOnlyRecovery(
                            category = EngineFailureCategory.STORAGE,
                            code = "projection_scan_deadline_exceeded",
                            retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                            diagnostic = "scan deadline exceeded",
                        ),
                        location = StorageLocation("/vault"),
                        authority = null,
                        freshness = ProjectionFreshness.Unavailable,
                    )
                runCurrent()
                viewModel.rootOperationState.value shouldBe
                    WorkspaceRootOperationState.Failed("projection_scan_deadline_exceeded")
            }
        }

        test("git feature viewmodel exposes wired coordinator delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val gitSupport = FakeGitSupport()

            val viewModel = SettingsGitFeatureViewModel(actionCoordinator, actionCoordinator, gitSupport)
            viewModel.provider.updateEnabled(true)
            viewModel.updateGitRemoteUrl("https://example.com/repo.git")
            viewModel.provider.testConnection()
            viewModel.resetGitRepository()
            viewModel.provider.resetConnectionTestState()

            (actionCoordinator.providerEnabledArg) shouldBe (SyncBackendType.GIT to true)
            (actionCoordinator.gitRemoteUrlArg) shouldBe ("https://example.com/repo.git")
            (actionCoordinator.testProviderArg) shouldBe SyncBackendType.GIT
            ((actionCoordinator.resetGitRepositoryInvoked)) shouldBe true
            (gitSupport.resetConnectionTestStateInvocations) shouldBe (1)
            ((viewModel.isValidGitRemoteUrl("https://example.com/repo.git"))) shouldBe true
        }

        test("webdav feature viewmodel exposes wired coordinator delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val webDavSupport = FakeWebDavSupport()

            val viewModel = SettingsWebDavFeatureViewModel(actionCoordinator, actionCoordinator, webDavSupport)
            viewModel.provider.updateEnabled(true)
            viewModel.updateProvider(WebDavProvider.NUTSTORE)
            viewModel.provider.triggerSyncNow()
            viewModel.provider.testConnection()
            viewModel.provider.resetConnectionTestState()

            (actionCoordinator.providerEnabledArg) shouldBe (SyncBackendType.WEBDAV to true)
            (actionCoordinator.webDavProviderArg) shouldBe (WebDavProvider.NUTSTORE)
            (actionCoordinator.triggerProviderArg) shouldBe SyncBackendType.WEBDAV
            (actionCoordinator.testProviderArg) shouldBe SyncBackendType.WEBDAV
            (webDavSupport.resetConnectionTestStateInvocations) shouldBe (1)
            ((viewModel.isValidUrl("https://dav.example.com"))) shouldBe true
            ((viewModel.isValidWebDavUrl("https://dav.example.com"))) shouldBe true
        }

        test("s3 feature viewmodel exposes wired coordinator delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val s3Support = FakeS3Support()

            val viewModel = SettingsS3FeatureViewModel(actionCoordinator, actionCoordinator, s3Support)
            viewModel.provider.updateEnabled(true)
            viewModel.updateBucket("vault")
            viewModel.updateLocalSyncDirectory("content://tree/primary%3AObsidian")
            viewModel.clearLocalSyncDirectory()
            viewModel.updatePathStyle(S3PathStyle.PATH_STYLE)
            viewModel.updateEncryptionMode(S3EncryptionMode.RCLONE_CRYPT)
            viewModel.updateEncryptionPassword2("secret-salt")
            viewModel.updateRcloneFilenameEncryption(S3RcloneFilenameEncryption.OFF)
            viewModel.updateRcloneFilenameEncoding(S3RcloneFilenameEncoding.BASE32768)
            viewModel.updateRcloneDirectoryNameEncryption(false)
            viewModel.updateRcloneDataEncryptionEnabled(false)
            viewModel.updateRcloneEncryptedSuffix("none")
            viewModel.provider.triggerSyncNow()
            viewModel.provider.testConnection()
            viewModel.provider.resetConnectionTestState()

            (actionCoordinator.providerEnabledArg) shouldBe (SyncBackendType.S3 to true)
            (actionCoordinator.s3BucketArg) shouldBe ("vault")
            (actionCoordinator.s3LocalSyncDirectoryArg) shouldBe ("content://tree/primary%3AObsidian")
            (actionCoordinator.s3PathStyleArg) shouldBe (S3PathStyle.PATH_STYLE)
            (actionCoordinator.s3EncryptionModeArg) shouldBe (S3EncryptionMode.RCLONE_CRYPT)
            (actionCoordinator.s3EncryptionPassword2Arg) shouldBe ("secret-salt")
            (actionCoordinator.s3FilenameEncryptionArg) shouldBe (S3RcloneFilenameEncryption.OFF)
            (actionCoordinator.s3FilenameEncodingArg) shouldBe (S3RcloneFilenameEncoding.BASE32768)
            (actionCoordinator.s3DirectoryNameEncryptionArg) shouldBe (false)
            (actionCoordinator.s3DataEncryptionArg) shouldBe (false)
            (actionCoordinator.s3EncryptedSuffixArg) shouldBe ("none")
            ((actionCoordinator.clearS3LocalSyncDirectoryInvoked)) shouldBe true
            (actionCoordinator.triggerProviderArg) shouldBe SyncBackendType.S3
            (actionCoordinator.testProviderArg) shouldBe SyncBackendType.S3
            (s3Support.resetConnectionTestStateInvocations) shouldBe (1)
            ((viewModel.isValidEndpointUrl("https://s3.example.com"))) shouldBe true
        }
    }
}

private class FakeSettingsActionCoordinator :
    SettingsLanShareFeatureActions,
    SettingsRemoteProviderFeatureActions,
    SettingsGitSpecificFeatureActions,
    SettingsWebDavSpecificFeatureActions,
    SettingsS3SpecificFeatureActions {
    var lanShareEnabledArg: Boolean? = null
    var lanShareDeviceNameArg: String? = null

    var providerEnabledArg: Pair<SyncBackendType, Boolean>? = null
    var triggerProviderArg: SyncBackendType? = null
    var testProviderArg: SyncBackendType? = null
    var gitRemoteUrlArg: String? = null
    var resetGitRepositoryInvoked = false

    var webDavProviderArg: WebDavProvider? = null

    var s3BucketArg: String? = null
    var s3LocalSyncDirectoryArg: String? = null
    var clearS3LocalSyncDirectoryInvoked = false
    var s3PathStyleArg: S3PathStyle? = null
    var s3EncryptionModeArg: S3EncryptionMode? = null
    var s3EncryptionPassword2Arg: String? = null
    var s3FilenameEncryptionArg: S3RcloneFilenameEncryption? = null
    var s3FilenameEncodingArg: S3RcloneFilenameEncoding? = null
    var s3DirectoryNameEncryptionArg: Boolean? = null
    var s3DataEncryptionArg: Boolean? = null
    var s3EncryptedSuffixArg: String? = null

    override val updateLanShareEnabled: (Boolean) -> Unit = { lanShareEnabledArg = it }
    override val updateLanShareDeviceName: (String) -> Unit = { lanShareDeviceNameArg = it }

    override val updateProviderEnabled: (SyncBackendType, Boolean) -> Unit =
        { provider, enabled -> providerEnabledArg = provider to enabled }
    override val updateProviderAutoSyncEnabled: (SyncBackendType, Boolean) -> Unit = { _, _ -> }
    override val updateProviderAutoSyncInterval: (SyncBackendType, String) -> Unit = { _, _ -> }
    override val updateProviderSyncOnRefresh: (SyncBackendType, Boolean) -> Unit = { _, _ -> }
    override val triggerProviderSyncNow: (SyncBackendType) -> Unit = { provider -> triggerProviderArg = provider }
    override val testProviderConnection: (SyncBackendType) -> Unit = { provider -> testProviderArg = provider }

    override val updateGitRemoteUrl: (String) -> Unit = { gitRemoteUrlArg = it }
    override val updateGitBranch: (String) -> Unit = {}
    override val updateGitPat: (String) -> Unit = {}
    override val updateGitAuthorName: (String) -> Unit = {}
    override val updateGitAuthorEmail: (String) -> Unit = {}
    override val resetGitRepository: () -> Unit = { resetGitRepositoryInvoked = true }

    override val updateWebDavProvider: (WebDavProvider) -> Unit = { webDavProviderArg = it }
    override val updateWebDavBaseUrl: (String) -> Unit = {}
    override val updateWebDavEndpointUrl: (String) -> Unit = {}
    override val updateWebDavUsername: (String) -> Unit = {}
    override val updateWebDavPassword: (String) -> Unit = {}

    override val updateS3EndpointUrl: (String) -> Unit = {}
    override val updateS3Region: (String) -> Unit = {}
    override val updateS3Bucket: (String) -> Unit = { s3BucketArg = it }
    override val updateS3Prefix: (String) -> Unit = {}
    override val updateS3LocalSyncDirectory: (String) -> Unit = { s3LocalSyncDirectoryArg = it }
    override val clearS3LocalSyncDirectory: () -> Unit = { clearS3LocalSyncDirectoryInvoked = true }
    override val updateS3AccessKeyId: (String) -> Unit = {}
    override val updateS3SecretAccessKey: (String) -> Unit = {}
    override val updateS3SessionToken: (String) -> Unit = {}
    override val updateS3PathStyle: (S3PathStyle) -> Unit = { s3PathStyleArg = it }
    override val updateS3EncryptionMode: (S3EncryptionMode) -> Unit = { s3EncryptionModeArg = it }
    override val updateS3EncryptionPassword: (String) -> Unit = {}
    override val updateS3EncryptionPassword2: (String) -> Unit = { s3EncryptionPassword2Arg = it }
    override val updateS3RcloneFilenameEncryption: (S3RcloneFilenameEncryption) -> Unit = {
        s3FilenameEncryptionArg = it
    }
    override val updateS3RcloneFilenameEncoding: (S3RcloneFilenameEncoding) -> Unit = { s3FilenameEncodingArg = it }
    override val updateS3RcloneDirectoryNameEncryption: (Boolean) -> Unit = { s3DirectoryNameEncryptionArg = it }
    override val updateS3RcloneDataEncryptionEnabled: (Boolean) -> Unit = { s3DataEncryptionArg = it }
    override val updateS3RcloneEncryptedSuffix: (String) -> Unit = { s3EncryptedSuffixArg = it }
}

private class FakeGitSupport : SettingsGitFeatureSupport {
    var resetConnectionTestStateInvocations = 0

    override val isValidGitRemoteUrl: (String) -> Boolean = { url -> url.startsWith("https://") }
    override val isValidGitBranch: (String) -> Boolean = { branch -> branch.isNotBlank() }
    override val resetConnectionTestState: () -> Unit = {
        resetConnectionTestStateInvocations += 1
    }
}

private class FakeWebDavSupport : SettingsWebDavFeatureSupport {
    var resetConnectionTestStateInvocations = 0

    override fun resetConnectionTestState() {
        resetConnectionTestStateInvocations += 1
    }

    override fun isValidWebDavUrl(url: String): Boolean = url.startsWith("https://")
}

private class FakeS3Support : SettingsS3FeatureSupport {
    var resetConnectionTestStateInvocations = 0

    override fun resetConnectionTestState() {
        resetConnectionTestStateInvocations += 1
    }

    override fun isValidEndpointUrl(url: String): Boolean = url.startsWith("https://")
}

private fun verifiedStorageMount(freshness: ProjectionFreshness = ProjectionFreshness.Verified(1uL)): WorkspaceMount =
    WorkspaceMount(
        readiness = EngineReadiness.Ready,
        location = StorageLocation("/vault"),
        authority = WorkspaceAuthority(workspaceId = "ws", generation = 1, projectionRevision = 1uL),
        freshness = freshness,
    )
