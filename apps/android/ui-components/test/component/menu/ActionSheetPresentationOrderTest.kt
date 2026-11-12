// architectural-boundary-check
package com.lomo.ui.component.menu

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Unit under test: shared action sheet module boundary.
 * - Owning layer: ui-components presentation.
 * - Priority tier: P1.
 * - Capability: keep the reusable action sheet free of feature-owned menu identities, business
 *   callback names and compatibility payload accessors.
 *
 * Scenarios:
 * - Given shared menu source files, when the boundary test scans them, then feature-owned menu
 *   identities, business callback names, and compatibility payload accessors are absent.
 *
 * Observable outcomes:
 * - Empty offender list for the menu boundary scan.
 *
 * TDD proof:
 * - RED: the parent acceptance audit failed because shared menu sources retained feature-owned
 *   action identities, business callbacks, and a compatibility payload accessor.
 *
 * Excludes:
 * - Compose rendering and layout planning (covered by ActionSheetLayoutPlanTest) and app-layer
 *   action semantics.
 *
 * Production scope: apps/android/ui-components/src/component/menu/MemoActionSheet.kt
 * Production scope: apps/android/ui-components/src/component/menu/MemoActionSheetDefaults.kt
 *
 * Test Change Justification:
 * - Reason category: product feature removal (usage-based memo menu auto-reorder).
 * - Old behavior/assertion being replaced: sortActionItemUiByPreferredKeys ranking, duplicate
 *   stable-key rejection and generated render-key uniqueness for unkeyed actions.
 * - Why old assertion is no longer correct: the menu redesign uses a fixed tile/list layout, so
 *   preferred-key sorting, stable presentation keys and LazyRow render keys no longer exist.
 * - Coverage preserved by: the boundary scan is unchanged; section ordering, destructive row
 *   placement and the tile limit are asserted by ActionSheetLayoutPlanTest.
 * - Why this is not fitting the test to the implementation: the removed functions were deleted
 *   with the feature; no remaining production behavior lost its assertion.
 */
class ActionSheetPresentationOrderTest : UiComponentsFunSpec() {
    private val menuSourceRoot =
        resolveModuleRoot("ui-components").resolve("src/component/menu")

    init {
        test("given shared menu sources when scanned then feature-owned menu surface is absent") {
            val offenders =
                menuSourceRoot
                    .walkTopDown()
                    .filter { file -> file.isFile && file.extension == "kt" }
                    .flatMap { file ->
                        file
                            .readLines()
                            .withIndex()
                            .filter { (_, line) -> forbiddenBoundaryTerms().any(line::contains) }
                            .map { (index, line) -> "${file.relativeTo(menuSourceRoot)}:${index + 1}:$line" }
                    }
                    .toList()

            withClue("ui-components menu must expose only generic presentation actions. Offenders: $offenders") {
                offenders shouldBe emptyList()
            }
        }
    }

    private fun forbiddenBoundaryTerms(): List<String> = listOf(
        "Memo" + "Action" + "Id",
        "Memo" + "Action" + "Sheet" + "Action",
        "Memo" + "Action" + "Haptic",
        "LAN" + "_" + "SHARE",
        "P" + "IN",
        "J" + "UMP",
        "HIST" + "ORY",
        "version" + "-" + "history",
        "on" + "Lan" + "Share",
        "on" + "Toggle" + "Pin",
        "on" + "History",
        "on" + "Memo" + "Action",
        "on" + "Edit",
        "on" + "Delete",
        "Pay" + "load",
        "with" + "Pay" + "load",
        "memo" + "As",
        "memo" + ": " + "Any?",
        "val " + "memo" + ":",
        "state" + "." + "memo",
    )

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
                dir.name == moduleName && dir.resolve("module.yaml").exists()
            },
        ) {
            "Failed to resolve $moduleName module root from $currentDirPath"
        }
    }
}
