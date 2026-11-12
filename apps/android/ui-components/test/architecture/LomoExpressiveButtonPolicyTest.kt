// architectural-boundary-check
package com.lomo.ui.architecture

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Capability: UI production sources use the shared expressive button API.
 * - Behavior focus: production sources must reach the M3 Expressive `shapes:` overloads through
 *   the Lomo* wrapper composables in component/common/LomoButtons.kt; direct calls to the
 *   platform button composables resolve to the legacy static-shape overload and are forbidden.
 *
 * Scenarios:
 * - Given the wrapper file, when it is inspected, then it forwards to the `shapes:` overloads
 *   with the expressive defaults.
 * - Given all ui-components production sources, when they are scanned for bare platform button
 *   calls, direct material3 button imports and fully-qualified calls, then zero offenders are
 *   found.
 *
 * Observable outcomes: the Lomo* wrappers exist and the forbidden-call scan reports zero
 *   offenders.
 *
 * TDD proof: fails before the expressive-button migration because the scan finds dozens of bare
 *   IconButton(/Button(/TextButton( call sites and material3 button imports.
 *
 * Production scope: apps/android/ui-components/src/component/common/LomoButtons.kt
 *
 * Excludes: test sources, generated code, third-party libraries, comments, and the wrapper file
 *   itself (it is the only place allowed to call the platform composables). ToggleButton and
 *   IconToggleButton are separate expressive APIs not covered by this contract.
 * Test Change Justification:
 * Reason category: architecture test relocation.
 * Old behavior/assertion being replaced: All wrapper and forbidden-call assertions remain unchanged.
 * Why old assertion is no longer correct: existing outcome assertions remain valid and retained.
 * Coverage preserved by: The wrapper exemption and all remaining UI production files are still checked.
 * Why this is not fitting the test to the implementation: no expected product outcome is weakened.
 */
class LomoExpressiveButtonPolicyTest : UiComponentsFunSpec() {
    private val moduleRoot = resolveModuleRoot("ui-components")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        registerPolicyControls()
        test("LomoButtons.kt forwards to the expressive shapes overloads") {
            val source = sourceRoot.resolve("component/common/LomoButtons.kt")
            withClue("component/common/LomoButtons.kt must exist") { source.exists() shouldBe true }
            val content = source.readText()
            listOf(
                "LomoIconButton", "LomoFilledIconButton", "LomoFilledTonalIconButton",
                "LomoOutlinedIconButton", "LomoButton", "LomoTextButton",
                "LomoFilledTonalButton", "LomoOutlinedButton", "LomoElevatedButton",
            ).forEach { wrapper ->
                withClue("LomoButtons.kt must define $wrapper") {
                    content.contains("fun $wrapper(") shouldBe true
                }
            }
            withClue("wrappers must default to the expressive IconButton shapes") {
                content.contains("IconButtonDefaults.shapes()") shouldBe true
            }
            withClue("wrappers must default to the expressive Button shapes") {
                content.contains("ButtonDefaults.shapes()") shouldBe true
            }
        }

        test("no production source calls platform button composables directly") {
            val kotlinFiles =
                sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under ui-components/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }
            val offenders =
                kotlinFiles
                    .filterNot { file -> file.path.endsWith("component/common/LomoButtons.kt") }
                    .flatMap { file -> forbiddenCallSites(file.name, file.readLines()) }
            withClue(
                "Button composables must be reached through Lomo* wrappers so the expressive " +
                    "pressed-shape morph applies. Offenders: ${offenders.joinToString()}",
            ) {
                offenders.isEmpty() shouldBe true
            }
        }
    }

    private fun registerPolicyControls() {
        test("given direct platform APIs when scanned then imports bare and qualified calls are rejected") {
            listOf(
                "import androidx.compose.material3.Button",
                "IconButton(onClick = action) {}",
                "androidx.compose.material3.TextButton(onClick = action) {}",
            ).forEach { line ->
                withClue(line) {
                    forbiddenCallSites("Fixture.kt", listOf(line)) shouldBe listOf("Fixture.kt:1")
                }
            }
        }
        test("given shared wrappers and comments when scanned then permitted source has no offenders") {
            forbiddenCallSites(
                "Fixture.kt",
                listOf(
                    "LomoIconButton(onClick = action) {}",
                    "import com.lomo.ui.component.common.LomoButton",
                    "// IconButton(onClick = action) {}",
                ),
            ) shouldBe emptyList()
        }
    }

    private fun forbiddenCallSites(name: String, lines: List<String>): List<String> = lines
        .mapIndexedNotNull { index, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapIndexedNotNull null
            if (FORBIDDEN_CALL_PATTERN.containsMatchIn(line) ||
                FORBIDDEN_IMPORT_PATTERN.containsMatchIn(line) ||
                FORBIDDEN_FQN_PATTERN.containsMatchIn(line)
            ) {
                "$name:${index + 1}"
            } else {
                null
            }
        }

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
        private const val BUTTON_NAMES =
            "(?:IconButton|FilledIconButton|FilledTonalIconButton|OutlinedIconButton|Button|" +
                "ElevatedButton|FilledTonalButton|OutlinedButton|TextButton)"

        private val FORBIDDEN_CALL_PATTERN =
            Regex("""(?<![A-Za-z.])$BUTTON_NAMES\s*\(""")

        private val FORBIDDEN_IMPORT_PATTERN =
            Regex("""^import androidx\.compose\.material3\.$BUTTON_NAMES\s*$""")

        private val FORBIDDEN_FQN_PATTERN =
            Regex("""androidx\.compose\.material3\.$BUTTON_NAMES\s*\(""")
    }
}
