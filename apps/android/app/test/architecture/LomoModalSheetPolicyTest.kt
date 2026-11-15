package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * Capability: unified modal bottom sheet wrapper and removal of duplicated per-screen drag handles in app;
 * owner: app UI architecture; priority: P1.
 *
 * Scenarios:
 * - Given app production sources, when scanning for bare Material3 ModalBottomSheet( calls,
 *   then zero callers bypass LomoModalBottomSheet.
 * - Given app production sources, when scanning for legacy ad-hoc sheet drag handles,
 *   then zero duplicate drag handle composables remain.
 *
 * Observable outcomes: zero direct ModalBottomSheet callers and zero duplicate drag handle functions in app/src.
 * TDD proof: RED while ThemePreferenceDialog, MemoVersionHistorySheet, MainMemoFilterPanel,
 * DailyReviewOptionsSheet, QuickBallPresentation, and TrashScreen call ModalBottomSheet( directly and
 * define MainMemoFilterDragHandle / DailyReviewFilterDragHandle / QuickBallSheetDragHandle.
 * Source inventory: all app production Kotlin files under apps/android/app/src/.
 * Excludes: test sources and generated code.
 */
class LomoModalSheetPolicyTest : AppFunSpec() {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        test("app production sources use LomoModalBottomSheet instead of bare ModalBottomSheet") {
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under app/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }

            val offenders = kotlinFiles.filter { file -> file.callsBareModalBottomSheet() }
            withClue(
                "All app modal sheets must delegate to LomoModalBottomSheet. " +
                    "Offenders: ${offenders.joinToString { it.relativeTo(sourceRoot).path }}",
            ) {
                offenders.isEmpty() shouldBe true
            }
        }

        test("legacy duplicate sheet drag handles are deleted") {
            val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            val legacyHandles =
                listOf(
                    "MainMemoFilterDragHandle",
                    "DailyReviewFilterDragHandle",
                    "QuickBallSheetDragHandle",
                )
            val offenders =
                kotlinFiles.filter { file ->
                    val content = file.readText()
                    legacyHandles.any { handle -> content.contains(handle) }
                }

            withClue(
                "Legacy per-screen drag handles must be replaced by LomoModalBottomSheet's shared handle. " +
                    "Offenders: ${offenders.joinToString { it.relativeTo(sourceRoot).path }}",
            ) {
                offenders.isEmpty() shouldBe true
            }
        }

        test("LomoAppRoot provides LocalLomoHazeState and marks app root as a Haze source") {
            val appRoot = sourceRoot.resolve("LomoAppRoot.kt").readText()
            withClue(
                "LomoAppRoot must provide LocalLomoHazeState across windows so radial share overlay blurs full backdrop",
            ) {
                appRoot.contains("LocalLomoHazeState") shouldBe true
                appRoot.contains(".hazeSource(") shouldBe true
            }
        }
    }

    private fun File.callsBareModalBottomSheet(): Boolean = readLines()
        .filterNot { line -> line.trimStart().startsWith("import") }
        .filterNot { line -> line.trimStart().startsWith("//") }
        .filterNot { line -> line.trimStart().startsWith("*") }
        .any { line -> BARE_MODAL_SHEET_CALL.containsMatchIn(line) }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDir = File(System.getProperty("user.dir") ?: ".")
        val candidateRoots =
            listOf(
                currentDir,
                currentDir.resolve(moduleName),
                currentDir.parentFile?.resolve(moduleName),
                currentDir.parentFile?.parentFile?.resolve(moduleName),
            )
        return candidateRoots.filterNotNull().firstOrNull { it.resolve("src").exists() }
            ?: currentDir.resolve(moduleName)
    }

    private companion object {
        private val BARE_MODAL_SHEET_CALL = Regex("""\bModalBottomSheet\s*\(""")
    }
}
