package com.lomo.app.feature.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: Main-screen new-memo insert orchestration source contract.
 * - Behavior focus: prepend orchestration must advance directly from "new first item detected at absolute top"
 *   into blank-space preparation, skip any post-insert top-repin branch, and start reveal immediately after
 *   space preparation without a second gate.
 * - Observable outcomes: required and forbidden source snippets in the orchestration policy and screen effect.
 * - Red phase: Fails before the fix because MainScreen still keeps the post-insert top-pin branch and the policy
 *   still encodes viewport-top recovery instead of direct staged insertion.
 * - Excludes: Compose runtime interpolation, device scroll physics, repository persistence, and Hilt wiring.
 */
class MainScreenNewMemoAnimationOrchestrationContractTest {
    private val moduleRoot = resolveModuleRoot("app")
    private val policyFile =
        moduleRoot.resolve(
            "src/main/java/com/lomo/app/feature/main/MainScreenNewMemoInsertAnimationPolicy.kt",
        )
    private val screenFile =
        moduleRoot.resolve(
            "src/main/java/com/lomo/app/feature/main/MainScreen.kt",
        )

    @Test
    fun `main screen keeps single top-recovery gate and immediate post-space reveal`() {
        val content =
            listOf(policyFile, screenFile)
                .joinToString(separator = " ") { it.readText() }
                .normalizeWhitespace()

        assertTrue(
            """
            Main-screen new-memo orchestration must use the real top viewport memo to decide whether the inserted
            top memo is ready, and must not keep a second reveal gate or repeated fallback repin branch.
            Expected contract across:
            ${policyFile.path}
            ${screenFile.path}
            """.trimIndent(),
            REQUIRED_SNIPPETS.all(content::contains) &&
                FORBIDDEN_SNIPPETS.none(content::contains),
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

    private companion object {
        val REQUIRED_SNIPPETS =
            listOf(
                "currentTopViewportMemoId: String?",
                "currentState.gapReadyMemoId != null -> {",
                "currentState.awaitingInsertedTopMemo -> {",
                "newMemoInsertAnimationSession.markRevealReady(currentState.gapReadyMemoId)",
                "isInsertedTopMemoReadyForSpaceStage(",
            )

        val FORBIDDEN_SNIPPETS =
            listOf(
                "shouldRequestPostInsertTopPin(",
                "currentTopViewportMemoId == currentListTopMemoId",
                "currentState.awaitingInsertedTopMemo &&",
                "newMemoInsertAnimationSession.markTopPinRequested()",
                "listState.requestScrollToItem(0)",
            )
    }
}
