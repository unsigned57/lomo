package com.lomo.app.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: settings sync-conflict routing wiring across SettingsScreen and SettingsScreenSupport.
 * - Behavior focus: Git, WebDAV, and S3 conflict states must all route into the shared SyncConflictDialog flow so users can resolve detected sync conflicts from settings instead of being left in a stuck status state.
 * - Observable outcomes: source-level wiring for SettingsScreen handler installation and SettingsScreenSupport conflict-dialog dispatch for WebDAV/S3.
 * - Red phase: Fails before the fix because SettingsScreen only wires Git conflict handling, so S3/WebDAV can report ConflictDetected without ever opening the shared resolution dialog.
 * - Excludes: Compose dialog rendering, repository conflict resolution internals, and transport-specific sync execution.
 */
class SettingsSyncConflictRoutingContractTest {
    private val settingsModuleRoot = resolveModuleRoot("app")
    private val settingsScreenSource =
        settingsModuleRoot.resolve("src/main/java/com/lomo/app/feature/settings/SettingsScreen.kt")
    private val settingsSupportSource =
        settingsModuleRoot.resolve("src/main/java/com/lomo/app/feature/settings/SettingsScreenSupport.kt")

    @Test
    fun `settings screen installs webdav and s3 conflict handlers`() {
        val content = settingsScreenSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SettingsScreen must wire WebDAV and S3 conflict handlers into the shared conflict dialog flow.
            Expected HandleWebDavConflictState(uiState.webDav.syncState, ...) and
            HandleS3ConflictState(uiState.s3.syncState, ...) in:
            ${settingsScreenSource.path}
            """.trimIndent(),
            content.contains("HandleWebDavConflictState(") &&
                content.contains("syncState = uiState.webDav.syncState") &&
                content.contains("HandleS3ConflictState(") &&
                content.contains("syncState = uiState.s3.syncState"),
        )
    }

    @Test
    fun `settings support dispatches webdav and s3 conflict states to shared dialog`() {
        val content = settingsSupportSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SettingsScreenSupport must open the shared conflict dialog for WebDAV and S3 ConflictDetected states.
            Expected HandleWebDavConflictState / HandleS3ConflictState to call
            conflictViewModel.showConflictDialog(conflictState.conflicts) in:
            ${settingsSupportSource.path}
            """.trimIndent(),
            content.contains("internal fun HandleWebDavConflictState(") &&
                content.contains("syncState as? WebDavSyncState.ConflictDetected") &&
                content.contains("internal fun HandleS3ConflictState(") &&
                content.contains("syncState as? S3SyncState.ConflictDetected") &&
                content.contains("conflictViewModel.showConflictDialog(conflictState.conflicts)"),
        )
    }

    private fun String.normalizeWhitespace(): String = replace(Regex("\\s+"), " ").trim()

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
            )
        return checkNotNull(
            candidateRoots.firstOrNull { dir ->
                dir.name == moduleName && dir.resolve("build.gradle.kts").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
