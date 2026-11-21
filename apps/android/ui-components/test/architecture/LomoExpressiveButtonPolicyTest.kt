// architectural-boundary-check
package com.lomo.ui.architecture

import com.lomo.ui.testing.UiComponentsFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Production UI calls the shared Lomo button wrappers. The wrapper file owns the direct
 * Material3 calls, and legal and forbidden fixtures validate the call-site scan.
 */
class LomoExpressiveButtonPolicyTest : UiComponentsFunSpec() {
    private val moduleRoot = resolveModuleRoot("ui-components")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        registerPolicyControls()
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
