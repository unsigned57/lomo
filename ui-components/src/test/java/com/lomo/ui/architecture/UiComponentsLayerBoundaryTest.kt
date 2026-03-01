package com.lomo.ui.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UiComponentsLayerBoundaryTest {
    private val moduleRoot = resolveModuleRoot("ui-components")
    private val sourceRoot = moduleRoot.resolve("src/main/java")

    @Test
    fun `ui-components source does not reference data layer package`() {
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("No Kotlin sources found under ui-components/src/main/java", kotlinFiles.isNotEmpty())

        val offenders = kotlinFiles.filter(::containsDataLayerReference)
        assertTrue(
            "ui-components module must not reference data layer package. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

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
    }
}
