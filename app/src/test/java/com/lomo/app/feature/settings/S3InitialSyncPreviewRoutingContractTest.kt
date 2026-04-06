package com.lomo.app.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: S3 initial-sync preview wiring across SettingsScreenSupport and SettingsErrorPresenter.
 * - Behavior focus: the S3 initial-sync preview state must route into the shared conflict dialog and surface preview-specific status copy instead of reusing the generic conflict subtitle.
 * - Observable outcomes: source-level routing for PreviewingInitialSync and preview-specific subtitle mapping.
 * - Red phase: Fails before the fix because the settings layer only recognizes S3 ConflictDetected, so first-sync overlap falls back to the old generic conflict path and users never see explicit preview messaging.
 * - Excludes: Compose dialog rendering, repository sync execution, and string-resource formatting behavior at runtime.
 */
class S3InitialSyncPreviewRoutingContractTest {
    private val settingsModuleRoot = resolveModuleRoot("app")
    private val settingsSupportSource =
        settingsModuleRoot.resolve("src/main/java/com/lomo/app/feature/settings/SettingsScreenSupport.kt")
    private val settingsPresenterSource =
        settingsModuleRoot.resolve("src/main/java/com/lomo/app/feature/settings/SettingsErrorPresenter.kt")
    private val defaultStrings = settingsModuleRoot.resolve("src/main/res/values/strings.xml")
    private val zhStrings = settingsModuleRoot.resolve("src/main/res/values-zh-rCN/strings.xml")

    @Test
    fun `settings support routes s3 initial sync preview into shared conflict dialog`() {
        val content = settingsSupportSource.readText().normalizeWhitespace()

        assertTrue(
            """
            SettingsScreenSupport must handle S3 PreviewingInitialSync by opening the shared conflict dialog.
            Expected HandleS3ConflictState to match S3SyncState.PreviewingInitialSync and call
            conflictViewModel.showConflictDialog(previewState.conflicts) in:
            ${settingsSupportSource.path}
            """.trimIndent(),
            content.contains("internal fun HandleS3ConflictState(") &&
                content.contains("syncState as? S3SyncState.PreviewingInitialSync") &&
                content.contains("conflictViewModel.showConflictDialog(previewState.conflicts)"),
        )
    }

    @Test
    fun `settings presenter exposes preview specific subtitle and resources`() {
        val presenterContent = settingsPresenterSource.readText().normalizeWhitespace()
        val defaultStringsContent = defaultStrings.readText()
        val zhStringsContent = zhStrings.readText()

        assertTrue(
            """
            SettingsErrorPresenter must map S3 PreviewingInitialSync to a dedicated subtitle string.
            Expected a PreviewingInitialSync branch using settings_s3_sync_status_initial_preview in:
            ${settingsPresenterSource.path}
            """.trimIndent(),
            presenterContent.contains("is S3SyncState.PreviewingInitialSync ->") &&
                presenterContent.contains("R.string.settings_s3_sync_status_initial_preview"),
        )
        assertTrue(
            "Default strings must define settings_s3_sync_status_initial_preview",
            defaultStringsContent.contains("name=\"settings_s3_sync_status_initial_preview\""),
        )
        assertTrue(
            "Simplified Chinese strings must define settings_s3_sync_status_initial_preview",
            zhStringsContent.contains("name=\"settings_s3_sync_status_initial_preview\""),
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
