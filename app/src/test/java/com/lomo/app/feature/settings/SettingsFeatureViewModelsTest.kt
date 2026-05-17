package com.lomo.app.feature.settings

import com.lomo.app.testing.AppFunSpec
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.WebDavProvider
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: non-app-config Settings*FeatureViewModel wrapper classes in SettingsFeatureViewModels.kt
 * - Behavior focus: direct delegate wiring for LAN/Git/WebDAV/S3 feature wrappers.
 * - Observable outcomes: forwarded action invocations, forwarded validation helpers, and coordinator-only resets.
 * - Red phase: Fails before behavior changes or migration are applied.
 * - Excludes: coordinator/use-case execution, DataStore internals, coroutine dispatch, and UI rendering.
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
    }

    init {
        test("git feature viewmodel exposes wired coordinator delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val gitSupport = FakeGitSupport()

            val viewModel = SettingsGitFeatureViewModel(actionCoordinator, gitSupport)
            viewModel.updateGitSyncEnabled(true)
            viewModel.updateGitRemoteUrl("https://example.com/repo.git")
            viewModel.testGitConnection()
            viewModel.resetGitRepository()
            viewModel.resetConnectionTestState()

            (actionCoordinator.gitSyncEnabledArg) shouldBe (true)
            (actionCoordinator.gitRemoteUrlArg) shouldBe ("https://example.com/repo.git")
            ((actionCoordinator.testGitConnectionInvoked)) shouldBe true
            ((actionCoordinator.resetGitRepositoryInvoked)) shouldBe true
            (gitSupport.resetConnectionTestStateInvocations) shouldBe (1)
            ((viewModel.isValidGitRemoteUrl("https://example.com/repo.git"))) shouldBe true
            ((viewModel.shouldShowGitConflictDialog(GitSyncErrorCode.UNKNOWN))) shouldBe false
        }
    }

    init {
        test("webdav feature viewmodel exposes wired coordinator delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val webDavSupport = FakeWebDavSupport()

            val viewModel = SettingsWebDavFeatureViewModel(actionCoordinator, webDavSupport)
            viewModel.updateWebDavSyncEnabled(true)
            viewModel.updateProvider(WebDavProvider.NUTSTORE)
            viewModel.triggerSyncNow()
            viewModel.testConnection()
            viewModel.resetConnectionTestState()

            (actionCoordinator.webDavSyncEnabledArg) shouldBe (true)
            (actionCoordinator.webDavProviderArg) shouldBe (WebDavProvider.NUTSTORE)
            ((actionCoordinator.triggerWebDavSyncNowInvoked)) shouldBe true
            ((actionCoordinator.testWebDavConnectionInvoked)) shouldBe true
            (webDavSupport.resetConnectionTestStateInvocations) shouldBe (1)
            ((viewModel.isValidUrl("https://dav.example.com"))) shouldBe true
            ((viewModel.isValidWebDavUrl("https://dav.example.com"))) shouldBe true
        }
    }

    init {
        test("s3 feature viewmodel exposes wired coordinator delegates") {
            val actionCoordinator = FakeSettingsActionCoordinator()
            val s3Support = FakeS3Support()

            val viewModel = SettingsS3FeatureViewModel(actionCoordinator, s3Support)
            viewModel.updateS3SyncEnabled(true)
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
            viewModel.triggerSyncNow()
            viewModel.testConnection()
            viewModel.resetConnectionTestState()

            (actionCoordinator.s3SyncEnabledArg) shouldBe (true)
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
            ((actionCoordinator.triggerS3SyncNowInvoked)) shouldBe true
            ((actionCoordinator.testS3ConnectionInvoked)) shouldBe true
            (s3Support.resetConnectionTestStateInvocations) shouldBe (1)
            ((viewModel.isValidEndpointUrl("https://s3.example.com"))) shouldBe true
        }
    }

}

private class FakeSettingsActionCoordinator :
    SettingsLanShareFeatureActions,
    SettingsGitFeatureActions,
    SettingsWebDavFeatureActions,
    SettingsS3FeatureActions {
    var lanShareEnabledArg: Boolean? = null
    var lanShareE2eEnabledArg: Boolean? = null
    var lanSharePairingCodeArg: String? = null
    var clearLanSharePairingCodeInvoked = false
    var lanShareDeviceNameArg: String? = null

    var gitSyncEnabledArg: Boolean? = null
    var gitRemoteUrlArg: String? = null
    var testGitConnectionInvoked = false
    var resetGitRepositoryInvoked = false

    var webDavSyncEnabledArg: Boolean? = null
    var webDavProviderArg: WebDavProvider? = null
    var triggerWebDavSyncNowInvoked = false
    var testWebDavConnectionInvoked = false

    var s3SyncEnabledArg: Boolean? = null
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
    var triggerS3SyncNowInvoked = false
    var testS3ConnectionInvoked = false

    override val updateLanShareEnabled: (Boolean) -> Unit = { lanShareEnabledArg = it }
    override val updateLanShareE2eEnabled: (Boolean) -> Unit = { lanShareE2eEnabledArg = it }
    override val updateLanSharePairingCode: (String) -> Unit = { lanSharePairingCodeArg = it }
    override val clearLanSharePairingCode: () -> Unit = { clearLanSharePairingCodeInvoked = true }
    override val updateLanShareDeviceName: (String) -> Unit = { lanShareDeviceNameArg = it }

    override val updateGitSyncEnabled: (Boolean) -> Unit = { gitSyncEnabledArg = it }
    override val updateGitRemoteUrl: (String) -> Unit = { gitRemoteUrlArg = it }
    override val updateGitPat: (String) -> Unit = {}
    override val updateGitAuthorName: (String) -> Unit = {}
    override val updateGitAuthorEmail: (String) -> Unit = {}
    override val updateGitAutoSyncEnabled: (Boolean) -> Unit = {}
    override val updateGitAutoSyncInterval: (String) -> Unit = {}
    override val updateGitSyncOnRefresh: (Boolean) -> Unit = {}
    override val triggerGitSyncNow: () -> Unit = {}
    override val resolveGitConflictUsingRemote: () -> Unit = {}
    override val resolveGitConflictUsingLocal: () -> Unit = {}
    override val testGitConnection: () -> Unit = { testGitConnectionInvoked = true }
    override val resetGitRepository: () -> Unit = { resetGitRepositoryInvoked = true }

    override val updateWebDavSyncEnabled: (Boolean) -> Unit = { webDavSyncEnabledArg = it }
    override val updateWebDavProvider: (WebDavProvider) -> Unit = { webDavProviderArg = it }
    override val updateWebDavBaseUrl: (String) -> Unit = {}
    override val updateWebDavEndpointUrl: (String) -> Unit = {}
    override val updateWebDavUsername: (String) -> Unit = {}
    override val updateWebDavPassword: (String) -> Unit = {}
    override val updateWebDavAutoSyncEnabled: (Boolean) -> Unit = {}
    override val updateWebDavAutoSyncInterval: (String) -> Unit = {}
    override val updateWebDavSyncOnRefresh: (Boolean) -> Unit = {}
    override val triggerWebDavSyncNow: () -> Unit = { triggerWebDavSyncNowInvoked = true }
    override val testWebDavConnection: () -> Unit = { testWebDavConnectionInvoked = true }

    override val updateS3SyncEnabled: (Boolean) -> Unit = { s3SyncEnabledArg = it }
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
    override val updateS3AutoSyncEnabled: (Boolean) -> Unit = {}
    override val updateS3AutoSyncInterval: (String) -> Unit = {}
    override val updateS3SyncOnRefresh: (Boolean) -> Unit = {}
    override val triggerS3SyncNow: () -> Unit = { triggerS3SyncNowInvoked = true }
    override val testS3Connection: () -> Unit = { testS3ConnectionInvoked = true }
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
