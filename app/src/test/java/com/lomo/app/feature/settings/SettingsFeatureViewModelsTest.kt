package com.lomo.app.feature.settings

import com.lomo.domain.model.GitSyncErrorCode
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
 * - Behavior focus: direct delegate wiring for LAN/Git/WebDAV feature wrappers.
 * - Observable outcomes: forwarded lambda invocations and coordinator helper exposure.
 * - Red phase: Not applicable - test-only coverage addition; no production change.
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
}
