package com.lomo.app.architecture

import com.lomo.app.testing.AppFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.File

/*
 * Behavior Contract:
 * - App production sources use semantic motion specs.
 * - The memo list wrapper preserves list identity when its animation or modifier changes.
 */
class LomoMotionPolicyTest : AppFunSpec() {
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src")

    init {
        test("memo list wrapper does not recompose content on modifier or animation identity") {
            val content = sourceRoot.resolve("feature/memo/MemoCardListAnimation.kt").readText()
            withClue("MemoCardList must not wrap MemoListContent in a key() group keyed on unstable identity") {
                content.contains("key(animation") shouldBe false
                content.contains("key(") shouldBe false
            }
        }

        test("no app production source declares bare tween, keyframes or spring specs") {
            val kotlinFiles =
                sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            withClue("No Kotlin sources found under app/src") {
                kotlinFiles.isNotEmpty() shouldBe true
            }
            val offenders = kotlinFiles.filter { file -> file.containsForbiddenSpec() }
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
    }
}
