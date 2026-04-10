package com.lomo.app.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: app baseline profile coverage for memo-menu expressive motion cold paths.
 * - Behavior focus: the shipped baseline profile must warm the expressive motion scheme and memo-menu sheet
 *   entry path so the first menu open does not pay a cold compilation path after launch.
 * - Observable outcomes: required baseline-profile entries in app/src/main/baseline-prof.txt for expressive
 *   motion and memo-menu host/sheet composition.
 * - Red phase: Fails before the fix because the app baseline profile does not cover MotionScheme.expressive or
 *   the memo-menu host and sheet entry methods that the first menu-open path relies on.
 * - Excludes: exact frame timing on device, benchmark generator internals, and non-menu cold paths.
 */
class ExpressiveMotionBaselineProfileContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val baselineProfile = appModuleRoot.resolve("src/main/baseline-prof.txt")

    @Test
    fun `baseline profile warms expressive motion and memo menu entry path`() {
        val content = baselineProfile.readText()

        assertTrue(
            """
            The app baseline profile must include the expressive motion entry point so first-open memo menus do
            not compile MotionScheme.expressive on demand.
            Missing expected entry in:
            ${baselineProfile.path}
            """.trimIndent(),
            content.contains(
                "SPLandroidx/compose/material3/MotionScheme\$Companion;->expressive()Landroidx/compose/material3/MotionScheme;",
            ),
        )
        assertTrue(
            """
            The app baseline profile must include the expressive motion implementation specs used by modal-sheet
            transitions on first open.
            Missing expected expressive spec entries in:
            ${baselineProfile.path}
            """.trimIndent(),
            content.contains(
                "SPLandroidx/compose/material3/MotionScheme\$ExpressiveMotionSchemeImpl;->defaultSpatialSpec()Landroidx/compose/animation/core/FiniteAnimationSpec;",
            ) &&
                content.contains(
                    "SPLandroidx/compose/material3/MotionScheme\$ExpressiveMotionSchemeImpl;->defaultEffectsSpec()Landroidx/compose/animation/core/FiniteAnimationSpec;",
                ),
        )
        assertTrue(
            """
            The app baseline profile must warm the memo-menu host and bottom-sheet composition path used on the
            first menu open after launch.
            Missing expected memo-menu entries in:
            ${baselineProfile.path}
            """.trimIndent(),
            content.contains(
                "SPLcom/lomo/ui/component/menu/MemoMenuHostKt;->MemoMenuHost(",
            ) &&
                content.contains(
                    "PLcom/lomo/ui/component/menu/MemoMenuBottomSheetKt;->MemoMenuBottomSheet(",
                ) &&
                content.contains(
                    "HPLcom/lomo/ui/component/menu/MemoActionSheetKt;->MemoActionSheet(",
                ),
        )
    }

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
