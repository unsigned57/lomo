package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.S3EncryptionMode
import com.lomo.domain.model.S3PathStyle
import com.lomo.domain.model.S3RcloneFilenameEncoding
import com.lomo.domain.model.S3RcloneFilenameEncryption
import com.lomo.domain.model.WebDavProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: non-app-config Settings*FeatureViewModel wrapper classes in SettingsFeatureViewModels.kt
 * - Behavior focus: direct delegate wiring for LAN/Git/WebDAV/S3 feature wrappers.
 * - Observable outcomes: forwarded lambda invocations and coordinator helper exposure.
 * - Red phase: Fails before the fix because the settings feature wrappers do not expose any S3 sync delegates.
 * - Excludes: SettingsAppConfigCoordinator coroutine dispatch, coordinator internal error mapping, DataStore internals, and UI rendering.
 */
class SettingsFeatureViewModelsTest {
    @Test
    fun `lan-share feature viewmodel forwards actions to delegates`() {
        val actionCoordinator = mockk<SettingsActionCoordinator>()
        val lanShareCoordinator = mockk<SettingsLanShareCoordinator>(relaxed = true)
        var e2eArg: Boolean? = null
        var pairingCodeArg: String? = null
        var clearPairingInvoked = false
        var deviceNameArg: String? = null
        every { actionCoordinator.updateLanShareE2eEnabled } returns { enabled -> e2eArg = enabled }
        every { actionCoordinator.updateLanSharePairingCode } returns { pairingCode -> pairingCodeArg = pairingCode }
        every { actionCoordinator.clearLanSharePairingCode } returns { clearPairingInvoked = true }
        every { actionCoordinator.updateLanShareDeviceName } returns { deviceName -> deviceNameArg = deviceName }

        val viewModel = SettingsLanShareFeatureViewModel(actionCoordinator, lanShareCoordinator)
        viewModel.updateLanShareE2eEnabled(true)
        viewModel.updateLanSharePairingCode("123456")
        viewModel.clearLanSharePairingCode()
        viewModel.clearPairingCodeError()
        viewModel.updateLanShareDeviceName("Pixel")

        assertEquals(true, e2eArg)
        assertEquals("123456", pairingCodeArg)
        assertTrue(clearPairingInvoked)
        assertEquals("Pixel", deviceNameArg)
        verify(exactly = 1) { lanShareCoordinator.clearPairingCodeError() }
    }

    @Test
    fun `git feature viewmodel exposes wired coordinator delegates`() {
        val actionCoordinator = mockk<SettingsActionCoordinator>()
        val gitCoordinator = mockk<SettingsGitCoordinator>()
        var gitSyncEnabledArg: Boolean? = null
        var gitRemoteUrlArg: String? = null
        var testConnectionInvoked = false
        var resetRepoInvoked = false
        var resetStateInvoked = false
        every { actionCoordinator.updateGitSyncEnabled } returns { enabled -> gitSyncEnabledArg = enabled }
        every { actionCoordinator.updateGitRemoteUrl } returns { url -> gitRemoteUrlArg = url }
        every { actionCoordinator.updateGitPat } returns {}
        every { actionCoordinator.updateGitAuthorName } returns {}
        every { actionCoordinator.updateGitAuthorEmail } returns {}
        every { actionCoordinator.updateGitAutoSyncEnabled } returns {}
        every { actionCoordinator.updateGitAutoSyncInterval } returns {}
        every { actionCoordinator.updateGitSyncOnRefresh } returns {}
        every { actionCoordinator.triggerGitSyncNow } returns {}
        every { actionCoordinator.resolveGitConflictUsingRemote } returns {}
        every { actionCoordinator.resolveGitConflictUsingLocal } returns {}
        every { actionCoordinator.testGitConnection } returns { testConnectionInvoked = true }
        every { actionCoordinator.resetGitRepository } returns { resetRepoInvoked = true }
        every { gitCoordinator.isValidGitRemoteUrl } returns { url -> url.startsWith("https://") }
        every { gitCoordinator.shouldShowGitConflictDialog } returns { code -> code == GitSyncErrorCode.CONFLICT }
        every { gitCoordinator.resetConnectionTestState } returns { resetStateInvoked = true }

        val viewModel = SettingsGitFeatureViewModel(actionCoordinator, gitCoordinator)
        viewModel.updateGitSyncEnabled(true)
        viewModel.updateGitRemoteUrl("https://example.com/repo.git")
        viewModel.testGitConnection()
        viewModel.resetGitRepository()
        viewModel.resetConnectionTestState()

        assertEquals(true, gitSyncEnabledArg)
        assertEquals("https://example.com/repo.git", gitRemoteUrlArg)
        assertTrue(testConnectionInvoked)
        assertTrue(resetRepoInvoked)
        assertTrue(resetStateInvoked)
        assertTrue(viewModel.isValidGitRemoteUrl("https://example.com/repo.git"))
        assertFalse(viewModel.shouldShowGitConflictDialog(GitSyncErrorCode.UNKNOWN))
    }

    @Test
    fun `webdav feature viewmodel exposes wired coordinator delegates`() {
        val actionCoordinator = mockk<SettingsActionCoordinator>()
        val webDavCoordinator = mockk<SettingsWebDavCoordinator>()
        var providerArg: WebDavProvider? = null
        var syncEnabledArg: Boolean? = null
        var syncNowInvoked = false
        var testConnectionInvoked = false
        var resetStateInvoked = false
        every { actionCoordinator.updateWebDavSyncEnabled } returns { enabled -> syncEnabledArg = enabled }
        every { actionCoordinator.updateWebDavProvider } returns { provider -> providerArg = provider }
        every { actionCoordinator.updateWebDavBaseUrl } returns {}
        every { actionCoordinator.updateWebDavEndpointUrl } returns {}
        every { actionCoordinator.updateWebDavUsername } returns {}
        every { actionCoordinator.updateWebDavPassword } returns {}
        every { actionCoordinator.updateWebDavAutoSyncEnabled } returns {}
        every { actionCoordinator.updateWebDavAutoSyncInterval } returns {}
        every { actionCoordinator.updateWebDavSyncOnRefresh } returns {}
        every { actionCoordinator.triggerWebDavSyncNow } returns { syncNowInvoked = true }
        every { actionCoordinator.testWebDavConnection } returns { testConnectionInvoked = true }
        every { webDavCoordinator.resetConnectionTestState() } answers { resetStateInvoked = true }
        every { webDavCoordinator.isValidWebDavUrl(any()) } returns true

        val viewModel = SettingsWebDavFeatureViewModel(actionCoordinator, webDavCoordinator)
        viewModel.updateWebDavSyncEnabled(true)
        viewModel.updateProvider(WebDavProvider.NUTSTORE)
        viewModel.triggerSyncNow()
        viewModel.testConnection()
        viewModel.resetConnectionTestState()

        assertEquals(true, syncEnabledArg)
        assertEquals(WebDavProvider.NUTSTORE, providerArg)
        assertTrue(syncNowInvoked)
        assertTrue(testConnectionInvoked)
        assertTrue(resetStateInvoked)
        assertTrue(viewModel.isValidUrl("https://dav.example.com"))
        assertTrue(viewModel.isValidWebDavUrl("https://dav.example.com"))
    }

    @Test
    fun `s3 feature viewmodel exposes wired coordinator delegates`() {
        val actionCoordinator = mockk<SettingsActionCoordinator>()
        val s3Coordinator = mockk<SettingsS3Coordinator>()
        var syncEnabledArg: Boolean? = null
        var bucketArg: String? = null
        var localSyncDirectoryArg: String? = null
        var pathStyleArg: S3PathStyle? = null
        var encryptionModeArg: S3EncryptionMode? = null
        var password2Arg: String? = null
        var filenameEncryptionArg: S3RcloneFilenameEncryption? = null
        var filenameEncodingArg: S3RcloneFilenameEncoding? = null
        var directoryNameEncryptionArg: Boolean? = null
        var dataEncryptionArg: Boolean? = null
        var encryptedSuffixArg: String? = null
        var clearLocalSyncDirectoryInvoked = false
        var syncNowInvoked = false
        var testConnectionInvoked = false
        var resetStateInvoked = false
        every { actionCoordinator.updateS3SyncEnabled } returns { enabled -> syncEnabledArg = enabled }
        every { actionCoordinator.updateS3EndpointUrl } returns {}
        every { actionCoordinator.updateS3Region } returns {}
        every { actionCoordinator.updateS3Bucket } returns { bucket -> bucketArg = bucket }
        every { actionCoordinator.updateS3Prefix } returns {}
        every { actionCoordinator.updateS3LocalSyncDirectory } returns { directory -> localSyncDirectoryArg = directory }
        every { actionCoordinator.clearS3LocalSyncDirectory } returns { clearLocalSyncDirectoryInvoked = true }
        every { actionCoordinator.updateS3AccessKeyId } returns {}
        every { actionCoordinator.updateS3SecretAccessKey } returns {}
        every { actionCoordinator.updateS3SessionToken } returns {}
        every { actionCoordinator.updateS3PathStyle } returns { style -> pathStyleArg = style }
        every { actionCoordinator.updateS3EncryptionMode } returns { mode -> encryptionModeArg = mode }
        every { actionCoordinator.updateS3EncryptionPassword } returns {}
        every { actionCoordinator.updateS3EncryptionPassword2 } returns { password -> password2Arg = password }
        every { actionCoordinator.updateS3RcloneFilenameEncryption } returns { mode -> filenameEncryptionArg = mode }
        every { actionCoordinator.updateS3RcloneFilenameEncoding } returns { encoding -> filenameEncodingArg = encoding }
        every { actionCoordinator.updateS3RcloneDirectoryNameEncryption } returns { enabled ->
            directoryNameEncryptionArg = enabled
        }
        every { actionCoordinator.updateS3RcloneDataEncryptionEnabled } returns { enabled -> dataEncryptionArg = enabled }
        every { actionCoordinator.updateS3RcloneEncryptedSuffix } returns { suffix -> encryptedSuffixArg = suffix }
        every { actionCoordinator.updateS3AutoSyncEnabled } returns {}
        every { actionCoordinator.updateS3AutoSyncInterval } returns {}
        every { actionCoordinator.updateS3SyncOnRefresh } returns {}
        every { actionCoordinator.triggerS3SyncNow } returns { syncNowInvoked = true }
        every { actionCoordinator.testS3Connection } returns { testConnectionInvoked = true }
        every { s3Coordinator.resetConnectionTestState() } answers { resetStateInvoked = true }
        every { s3Coordinator.isValidEndpointUrl(any()) } returns true

        val viewModel = SettingsS3FeatureViewModel(actionCoordinator, s3Coordinator)
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

        assertEquals(true, syncEnabledArg)
        assertEquals("vault", bucketArg)
        assertEquals("content://tree/primary%3AObsidian", localSyncDirectoryArg)
        assertEquals(S3PathStyle.PATH_STYLE, pathStyleArg)
        assertEquals(S3EncryptionMode.RCLONE_CRYPT, encryptionModeArg)
        assertEquals("secret-salt", password2Arg)
        assertEquals(S3RcloneFilenameEncryption.OFF, filenameEncryptionArg)
        assertEquals(S3RcloneFilenameEncoding.BASE32768, filenameEncodingArg)
        assertEquals(false, directoryNameEncryptionArg)
        assertEquals(false, dataEncryptionArg)
        assertEquals("none", encryptedSuffixArg)
        assertTrue(clearLocalSyncDirectoryInvoked)
        assertTrue(syncNowInvoked)
        assertTrue(testConnectionInvoked)
        assertTrue(resetStateInvoked)
        assertTrue(viewModel.isValidEndpointUrl("https://s3.example.com"))
    }
}
