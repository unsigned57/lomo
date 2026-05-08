package com.lomo.app.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: static baseline-profile rule/config contract for the app module.
 * - Behavior focus: app baseline rules must stay parseable, keep key cold-path coverage rules, and generated
 *   static baseline output must use the repository's ART-profile syntax when present.
 * - Observable outcomes: parseable non-empty rules, required rule coverage for main/menu/material3 packages,
 *   and generated profile lines that match class-only vs method-line syntax and include key app/menu entries.
 * - Red phase: Fails before the fix because app/baseline-rules.txt does not exist yet, so the app has no
 *   static baseline-profile contract input for generateReleaseStaticBaselineProfile.
 * - Excludes: ASM task internals, benchmark/device profile generation, and exact generated entry counts.
 */
class StaticBaselineProfileContractTest {
    private val appModuleRoot = resolveModuleRoot("app")
    private val rulesFile = appModuleRoot.resolve("baseline-rules.txt")
    private val generatedProfile = appModuleRoot.resolve("src/main/baselineProfiles/generated.txt")

    @Test
    fun `static baseline profile rules stay parseable and cover key cold paths`() {
        assertTrue(
            "Static baseline profile rules file must exist at ${rulesFile.path}",
            rulesFile.exists(),
        )

        val content = rulesFile.readText()
        val rules = parseRules(content)

        assertFalse(
            "Static baseline profile rules must not be empty: ${rulesFile.path}",
            rules.isEmpty(),
        )
        assertTrue(
            "Static baseline profile rules must cover app main cold paths.",
            content.contains("com/lomo/app/feature/main/**"),
        )
        assertTrue(
            "Static baseline profile rules must cover memo menu cold paths.",
            content.contains("com/lomo/ui/component/menu/**"),
        )
        assertTrue(
            "Static baseline profile rules must cover Material3 motion/menu paths.",
            content.contains("androidx/compose/material3/**"),
        )

        if (generatedProfile.exists()) {
            val profileLines =
                generatedProfile
                    .readLines()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }

            assertFalse(
                "Generated static baseline profile must contain meaningful entries: ${generatedProfile.path}",
                profileLines.isEmpty(),
            )
            profileLines.forEach { line ->
                assertTrue(
                    "Generated baseline profile line must follow repository syntax: $line",
                    CLASS_LINE_REGEX.matches(line) || METHOD_LINE_REGEX.matches(line),
                )
            }
            assertTrue(
                "Generated static baseline profile must contain MainActivity coverage.",
                profileLines.any { it.startsWith("Lcom/lomo/app/MainActivity;") },
            )
            assertTrue(
                "Generated static baseline profile must contain MemoMenuHostKt coverage.",
                profileLines.any { it.startsWith("Lcom/lomo/ui/component/menu/MemoMenuHostKt;") },
            )
        }
    }

    private fun parseRules(text: String): List<ParsedRule> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map(::parseLine)
            .toList()

    private fun parseLine(line: String): ParsedRule {
        val parts = line.split(Regex("\\s+"), limit = 2)
        if (parts.size != 2) {
            fail("Invalid baseline rule line: $line")
        }

        val flags = parts[0]
        if (!VALID_FLAGS.matches(flags)) {
            fail("Invalid baseline rule flags '$flags' in line: $line")
        }

        return ParsedRule(flags = flags, pattern = parts[1])
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

    private data class ParsedRule(
        val flags: String,
        val pattern: String,
    )

    companion object {
        private val VALID_FLAGS = Regex("[HSPL]+")
        private val CLASS_LINE_REGEX = Regex("L[\\w$/-]+;")
        private val METHOD_LINE_REGEX = Regex("[HSPL]+[\\w$/-]+;->[^\\(]+\\([^)]*\\).+")
    }
}
