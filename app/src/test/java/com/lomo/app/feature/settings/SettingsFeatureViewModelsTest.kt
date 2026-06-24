package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.WebDavProvider
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: non-app-config Settings*FeatureViewModel wrapper classes in SettingsFeatureViewModels.kt
 * - Owning layer: app/settings
 * - Priority tier: P2
 * - Capability: expose LAN actions directly while routing Git/WebDAV/S3 common provider actions
 *   through one provider-keyed wrapper plus provider-specific extension actions.
 *
 * Scenarios:
 * - Given LAN feature actions, when wrapper methods are invoked, then LAN actions and support hooks forward directly.
 * - Given Git/WebDAV/S3 common provider actions, when wrapper provider methods are invoked,
 *   then one shared provider-keyed action path receives the provider identity.
 * - Given provider-specific Git/WebDAV/S3 fields, when wrapper extension methods are invoked,
 *   then only provider-specific action interfaces receive those values.
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
 * - Coordinator/use-case execution, DataStore internals, coroutine dispatch, and UI rendering.
 *
 * Test Change Justification:
 * - Reason category: App layer restructuring replaced page-based memo retention and viewport delete animations with LomoList system, extracted provider settings dialogs, and added conflict/startup orchestration.
 * - Old behavior/assertion being replaced: previous app-layer tests relied on monolithic settings dialogs, DeleteViewportEntry animation system, and pre-LomoList memo retention.
 * - Why old assertion is no longer correct: the app layer was restructured: settings dialogs are now provider-specific, DeleteViewportEntry files are removed in favor of LomoList components, and paged memo content uses new pagination source.
 * - Coverage preserved by: all existing scenarios retained; assertions updated to use new LomoList animation contracts, provider settings surfaces, and paging source APIs.
 * - Why this is not fitting the test to the implementation: tests verify observable ViewModel state, UI coordinator behavior, and screen rendering outcomes, not internal animation or dialog mechanics.
 */
class SettingsFeatureViewModelsTest : AppFunSpec() {
    init {
        test("lan-share feature viewmodel forwards actions to delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val lanShareSupport = FakeLanShareSupport()

            val viewModel = SettingsLanShareFeatureViewModel(actionCoordinator, lanShareSupport)
            viewModel.updateLanShareEnabled(false)
            viewModel.updateLanShareE2eEnabled(true)
            viewModel.updateLanSharePairingCode("123456")
            viewModel.clearLanSharePairingCode()
            viewModel.clearPairingCodeError()
            viewModel.updateLanShareDeviceName("Pixel")

            (actionCoordinator.lanShareEnabledArg) shouldBe (false)
            (actionCoordinator.lanShareE2eEnabledArg) shouldBe (true)
            (actionCoordinator.lanSharePairingCodeArg) shouldBe ("123456")
            ((actionCoordinator.clearLanSharePairingCodeInvoked)) shouldBe true
            (actionCoordinator.lanShareDeviceNameArg) shouldBe ("Pixel")
            ((lanShareSupport.clearPairingCodeErrorInvoked)) shouldBe true
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
            ((viewModel.shouldShowGitConflictDialog(GitSyncErrorCode.UNKNOWN))) shouldBe false
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
    var lanShareE2eEnabledArg: Boolean? = null
    var lanSharePairingCodeArg: String? = null
    var clearLanSharePairingCodeInvoked = false
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
    override val updateLanShareE2eEnabled: (Boolean) -> Unit = { lanShareE2eEnabledArg = it }
    override val updateLanSharePairingCode: (String) -> Unit = { lanSharePairingCodeArg = it }
    override val clearLanSharePairingCode: () -> Unit = { clearLanSharePairingCodeInvoked = true }
    override val updateLanShareDeviceName: (String) -> Unit = { lanShareDeviceNameArg = it }

    override val updateProviderEnabled: (SyncBackendType, Boolean) -> Unit =
        { provider, enabled -> providerEnabledArg = provider to enabled }
    override val updateProviderAutoSyncEnabled: (SyncBackendType, Boolean) -> Unit = { _, _ -> }
    override val updateProviderAutoSyncInterval: (SyncBackendType, String) -> Unit = { _, _ -> }
    override val updateProviderSyncOnRefresh: (SyncBackendType, Boolean) -> Unit = { _, _ -> }
    override val triggerProviderSyncNow: (SyncBackendType) -> Unit = { provider -> triggerProviderArg = provider }
    override val testProviderConnection: (SyncBackendType) -> Unit = { provider -> testProviderArg = provider }

    override val updateGitRemoteUrl: (String) -> Unit = { gitRemoteUrlArg = it }
    override val updateGitPat: (String) -> Unit = {}
    override val updateGitAuthorName: (String) -> Unit = {}
    override val updateGitAuthorEmail: (String) -> Unit = {}
    override val resolveGitConflictUsingRemote: () -> Unit = {}
    override val resolveGitConflictUsingLocal: () -> Unit = {}
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
    override val updateS3RcloneFilenameEncryption: (S3RcloneFilenameEncryption) -> Unit = { s3FilenameEncryptionArg = it }
    override val updateS3RcloneFilenameEncoding: (S3RcloneFilenameEncoding) -> Unit = { s3FilenameEncodingArg = it }
    override val updateS3RcloneDirectoryNameEncryption: (Boolean) -> Unit = { s3DirectoryNameEncryptionArg = it }
    override val updateS3RcloneDataEncryptionEnabled: (Boolean) -> Unit = { s3DataEncryptionArg = it }
    override val updateS3RcloneEncryptedSuffix: (String) -> Unit = { s3EncryptedSuffixArg = it }
}

private class FakeLanShareSupport : SettingsLanShareFeatureSupport {
    var clearPairingCodeErrorInvoked = false

    override fun clearPairingCodeError() {
        clearPairingCodeErrorInvoked = true
    }
}

private class FakeGitSupport : SettingsGitFeatureSupport {
    var resetConnectionTestStateInvocations = 0

    override val isValidGitRemoteUrl: (String) -> Boolean = { url -> url.startsWith("https://") }
    override val shouldShowGitConflictDialog: (GitSyncErrorCode) -> Boolean = { code ->
        code == GitSyncErrorCode.CONFLICT
    }
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
