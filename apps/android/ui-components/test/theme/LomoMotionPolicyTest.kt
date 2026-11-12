// architectural-boundary-check
package com.lomo.ui.theme

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Capability: LomoMotion semantic scheme usage across ui-components production sources.
 * - Behavior focus: migrated motion call sites must resolve animation specs through LomoMotion
 *   (component/layout), and no production source may hand-roll bare tween/keyframes/spring specs
 *   outside the documented exceptions.
 *
 * Scenarios:
 * - Given the LomoMotion factory file, when it is inspected, then it exposes component and layout
 *   schemes through a CompositionLocal.
 * - Given a migrated call site, when its source is inspected, then it references the LomoMotion
 *   layer and does not read MaterialTheme.motionScheme directly.
 * - Given all ui-components production sources, when they are scanned for bare tween, keyframes or
 *   spring spec declarations outside the documented exceptions, then zero offenders are found.
 *
 * Observable outcomes: every migrated file references LomoMotion and no longer reads
 *   MaterialTheme.motionScheme; the forbidden-spec scan reports zero offenders.
 *
 * TDD proof: Fails before the LomoMotion migration because LomoListItemMotionSpecs.kt declares
 *   bare keyframes/tween/spring specs and MemoCard.kt reads MaterialTheme.motionScheme for
 *   animateContentSize.
 *
 * Production scope: apps/android/ui-components/src/theme/LomoMotion.kt
 * Production scope: apps/android/ui-components/src/component/common/LomoListItemMotionSpecs.kt
 * Production scope: apps/android/ui-components/src/component/card/MemoCard.kt
 * Production scope: apps/android/ui-components/src/component/input/InputSheet.kt
 * Production scope: apps/android/ui-components/src/component/navigation/PredictiveBack.kt
 * Production scope: apps/android/ui-components/src/component/navigation/SidebarDrawerTagTreeItems.kt
 * Production scope: apps/android/ui-components/src/component/menu/ActionSheetUi.kt
 *
 * Excludes: test sources, generated code, third-party libraries, and the documented exception
 *   list (Skeleton.kt keeps a duration-based infinite shimmer because infinite-repeatable
 *   animations cannot settle with a spring; LomoMotion.kt is the scheme factory).
 */
class LomoMotionPolicyTest : UiComponentsFunSpec() {
    private val moduleRoot = resolveModuleRoot("ui-components")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        test("lomo motion scheme factory exposes component and layout schemes through a CompositionLocal") {
            val source = sourceRoot.resolve("theme/LomoMotion.kt")
            withClue("theme/LomoMotion.kt must exist and define the semantic motion layer") {
                source.exists() shouldBe true
            }
            val content = source.readText()
            withClue("LomoMotion must expose a component scheme for expressive element motion") {
                content.contains("component") shouldBe true
            }
            withClue("LomoMotion must expose a layout scheme for standard layout motion") {
                content.contains("layout") shouldBe true
            }
            withClue("LomoMotion must be published through a CompositionLocal") {
                content.contains("LocalLomoMotion") shouldBe true
            }
        }

        test("migrated call sites resolve specs from LomoMotion and not MaterialTheme.motionScheme") {
            // Each file must name the semantic layer it resolves through: LocalLomoMotion for
            // direct scheme reads, or the list-item spec holder resolved from it transitively.
            val migratedFiles: List<Pair<String, String>> =
                listOf(
                    "component/card/MemoCard.kt" to "LocalLomoMotion",
                    "component/common/DraggableScrollbarOverlay.kt" to "LocalLomoMotion",
                    "component/common/LomoListItemMotionSpecs.kt" to "LocalLomoMotion",
                    "component/common/LomoListItemPhaseMotion.kt" to "rememberLomoListItemMotionSpecs",
                    "component/common/LomoStateContent.kt" to "LocalLomoMotion",
                    "component/input/InputSheet.kt" to "LocalLomoMotion",
                    "component/input/InputSheetComponents.kt" to "LocalLomoMotion",
                    "component/input/InputSheetContent.kt" to "LocalLomoMotion",
                    "component/input/InputSheetScaffoldComponents.kt" to "LocalLomoMotion",
                    "component/input/InputEditorActionBadge.kt" to "LocalLomoMotion",
                    "component/dialog/SyncConflictResolutionDialogCards.kt" to "LocalLomoMotion",
                    "component/navigation/PredictiveBack.kt" to "LocalLomoMotion",
                    "component/navigation/SidebarDrawerTagTreeItems.kt" to "LocalLomoMotion",
                    "component/media/AudioPlayerCard.kt" to "LocalLomoMotion",
                )
            migratedFiles.forEach { (relativePath, requiredMarker) ->
                val file = sourceRoot.resolve(relativePath)
                withClue("migrated motion call site $relativePath must exist") { file.exists() shouldBe true }
                val content = file.readText()
                withClue("$relativePath must resolve specs through the LomoMotion layer ($requiredMarker)") {
                    content.contains(requiredMarker) shouldBe true
                }
                withClue("$relativePath must not read MaterialTheme.motionScheme directly") {
                    content.contains("MaterialTheme.motionScheme") shouldBe false
                }
            }
        }

        test("no production source declares bare tween, keyframes or spring specs outside exceptions") {
            val kotlinFiles =
                sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under ui-components/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }
            val offenders =
                kotlinFiles
                    .filterNot { file -> ALLOWED_SPEC_FILES.any { file.path.endsWith(it) } }
                    .filter { file -> file.containsForbiddenSpec() }
            withClue(
                "bare tween/keyframes/spring specs must come from LomoMotion schemes. " +
                    "Offenders: ${offenders.joinToString { it.path }}",
            ) {
                offenders.isEmpty() shouldBe true
            }
        }
    }

    private fun File.containsForbiddenSpec(): Boolean = readLines()
        .filterNot { line -> line.trimStart().startsWith("import") }
        .filterNot { line -> line.trimStart().startsWith("//") }
        .filterNot { line -> line.trimStart().startsWith("*") }
        .any { line -> FORBIDDEN_SPEC_PATTERN.containsMatchIn(line) }

    private fun resolveModuleRoot(moduleName: String): File {
        val currentDirPath = System.getProperty("user.dir") ?: "."
        val currentDir = File(currentDirPath)
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
        private val FORBIDDEN_SPEC_PATTERN =
            Regex("""\b(?:tween|spring|SpringSpec)\s*[<(]|\bkeyframes\b""")
        private val ALLOWED_SPEC_FILES =
            listOf(
                // Infinite shimmer loops require a duration-based spec; springs never settle.
                "src/component/common/Skeleton.kt",
                // The scheme factory file owns spec construction.
                "src/theme/LomoMotion.kt",
            )
    }
}
