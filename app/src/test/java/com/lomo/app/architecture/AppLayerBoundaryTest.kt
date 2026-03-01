package com.lomo.app.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLayerBoundaryTest {
    private val kotlinFileExtension = "kt"
    private val moduleRoot = resolveModuleRoot("app")
    private val sourceRoot = moduleRoot.resolve("src/main/java")
    private val gradleFile = moduleRoot.resolve("build.gradle.kts")

    @Test
    fun `app source does not reference data layer package`() {
        val kotlinFiles = collectKotlinFiles()
        assertTrue("No Kotlin sources found in app/src/main/java", kotlinFiles.isNotEmpty())

        val offenders = kotlinFiles.filter(::containsDataLayerReference)
        assertTrue(
            "App layer must not reference data layer package. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `app module does not declare forbidden project data dependencies`() {
        val content = gradleFile.readText()
        val offenders =
            FORBIDDEN_DATA_DEPENDENCY_PATTERNS
                .filter { it.pattern.containsMatchIn(content) }
                .map { it.description }

        assertFalse(
            "app/build.gradle.kts must not declare compile dependency on data. Offenders: ${offenders.joinToString()}",
            offenders.isNotEmpty(),
        )
    }

    private fun collectKotlinFiles(): List<File> =
        sourceRoot
            .takeIf(File::exists)
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == kotlinFileExtension }
            ?.toList()
            .orEmpty()

    private fun containsDataLayerReference(file: File): Boolean {
        val content = file.readText()
        if (DATA_IMPORT_PATTERN.containsMatchIn(content)) return true
        val nonImportOrPackageContent = stripImportAndPackageLines(content)
        return DATA_FQCN_PATTERN.containsMatchIn(nonImportOrPackageContent)
    }

    private fun stripImportAndPackageLines(content: String): String =
        content
            .lineSequence()
            .filterNot { IMPORT_OR_PACKAGE_LINE_PATTERN.containsMatchIn(it) }
            .joinToString(separator = "\n")

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
        val DATA_IMPORT_PATTERN = Regex("""(?m)^\s*import\s+com\.lomo\.data(?:\.|$)""")
        val DATA_FQCN_PATTERN = Regex("""\bcom\.lomo\.data\.[A-Za-z_]\w*""")
        val IMPORT_OR_PACKAGE_LINE_PATTERN = Regex("""^\s*(import|package)\s+""")

        val FORBIDDEN_DATA_DEPENDENCY_PATTERNS =
            listOf(
                ForbiddenDependencyPattern(
                    description = "config(project(\":data\")) / config(project(path = \":data\"))",
                    pattern =
                        Regex(
                            """(?s)\b(?:implementation|api|compileOnly|ksp)\s*\(\s*project\s*\(\s*(?:path\s*=\s*)?['"]:data['"][^)]*\)\s*\)""",
                        ),
                ),
                ForbiddenDependencyPattern(
                    description = "config(projects.data)",
                    pattern =
                        Regex(
                            """(?s)\b(?:implementation|api|compileOnly|ksp)\s*\(\s*projects\.data\s*\)""",
                        ),
                ),
                ForbiddenDependencyPattern(
                    description = "add(\"config\", project(\":data\")) / add(\"config\", project(path = \":data\"))",
                    pattern =
                        Regex(
                            """(?s)\badd\s*\(\s*['"](?:implementation|api|compileOnly|ksp)['"]\s*,\s*project\s*\(\s*(?:path\s*=\s*)?['"]:data['"][^)]*\)\s*\)""",
                        ),
                ),
                ForbiddenDependencyPattern(
                    description = "add(\"config\", projects.data)",
                    pattern =
                        Regex(
                            """(?s)\badd\s*\(\s*['"](?:implementation|api|compileOnly|ksp)['"]\s*,\s*projects\.data\s*\)""",
                        ),
                ),
            )
    }

    private data class ForbiddenDependencyPattern(
        val description: String,
        val pattern: Regex,
    )
}
