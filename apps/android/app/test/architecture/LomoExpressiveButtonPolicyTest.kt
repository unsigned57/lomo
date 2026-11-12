package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - Capability: app production sources use the shared expressive button API.
 * - Behavior focus: app production sources must reach the M3 Expressive `shapes:` overloads
 *   through the Lomo* wrapper composables in ui-components component/common/LomoButtons.kt;
 *   direct calls to the platform button composables resolve to the legacy static-shape overload
 *   and are forbidden.
 *
 * Scenarios:
 * - Given all app production sources, when they are scanned for bare platform button calls,
 *   direct material3 button imports and fully-qualified calls, then zero offenders are found.
 *
 * Observable outcomes: the forbidden-call scan reports zero offenders.
 *
 * TDD proof: fails before the expressive-button migration because the scan finds dozens of bare
 *   IconButton(/Button(/TextButton( call sites and material3 button imports across the feature
 *   screens.
 *
 *
 * Excludes: test sources, generated code, third-party libraries and comments. ToggleButton and
 *   IconToggleButton are separate expressive APIs not covered by this contract.
 * Test Change Justification:
 * Reason category: architecture contract clarification.
 * Old behavior/assertion being replaced: The forbidden production call scan remains unchanged.
 * Why old assertion is no longer correct: existing outcome assertions remain valid and retained.
 * Coverage preserved by: Every app Kotlin source still participates in the scan.
 * Why this is not fitting the test to the implementation: no expected product outcome is weakened.
 */
class LomoExpressiveButtonPolicyTest : AppFunSpec() {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        registerPolicyControls()
        test("no app production source calls platform button composables directly") {
            val kotlinFiles =
                sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under app/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }
            val offenders =
                kotlinFiles.flatMap { file -> forbiddenCallSites(file.name, file.readLines()) }
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
