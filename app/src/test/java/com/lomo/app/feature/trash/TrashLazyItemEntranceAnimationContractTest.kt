package com.lomo.app.feature.trash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: TrashScreen lazy-item animation contract.
 * - Behavior focus: trash rows may collapse during delete/clear operations, but cached or newly
 *   visible rows must not replay a LazyColumn enter fade after that collapse settles.
 * - Observable outcomes: TrashScreen keeps animateItem placement support while disabling lazy
 *   fade-in for ordinary viewport entry.
 * - Red phase: Fails before the fix because TrashScreen wires a permanent keyframes fadeInSpec
 *   into every lazy item.
 * - Excludes: runtime Compose frame timing, exact delete easing curves, and repository state.
 */
/*
 * Test Change Justification:
 * - Reason category: architectural contract replacement.
 * - Old behavior/assertion being replaced: TrashScreen was expected to keep direct animateItem
 *   placement support with fadeInSpec = null.
 * - Why the old assertion is no longer correct: direct app animateItem calls are now forbidden;
 *   trash row movement must go through shared LazyListMotion, which owns the fade-in policy.
 * - Coverage preserved by: the test still requires no permanent keyframes fade, and now requires
 *   lazyListMotionItem adoption instead of local animateItem.
 * - Why this is not fitting the test to the implementation: the assertion encodes the root-cause
 *   fix requested by the user: one shared framework instead of per-screen patches.
 */
class TrashLazyItemEntranceAnimationContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceFile =
        moduleRoot.resolve("src/main/java/com/lomo/app/feature/trash/TrashScreen.kt")

    @Test
    fun `trash lazy rows do not keep permanent enter fade armed`() {
        val content = sourceFile.readText().normalizeWhitespace()

        assertTrue(
            """
            TrashScreen should keep placement animation for row movement, but lazy row enter fade
            must stay disabled so rows pulled into view after collapse do not replay entry motion.
            """.trimIndent(),
            content.contains(".lazyListMotionItem(") &&
                !content.contains(".animateItem(") &&
                !content.contains("fadeInSpec = keyframes"),
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
