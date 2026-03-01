package com.lomo.data.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLayerBoundaryTest {
    private val moduleRoot = resolveModuleRoot("data")
    private val sourceRoot = moduleRoot.resolve("src/main/java")

    @Test
    fun `data layer does not reference app layer package`() {
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("No Kotlin sources found under data/src/main/java", kotlinFiles.isNotEmpty())

        val offenders = kotlinFiles.filter(::containsAppLayerReference)

        assertTrue(
            "Data layer must not reference app layer package. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    private fun containsAppLayerReference(file: File): Boolean {
        val content = file.readText()
        if (APP_IMPORT_PATTERN.containsMatchIn(content)) return true
        val nonImportOrPackageContent = stripImportAndPackageLines(content)
        return APP_FQCN_PATTERN.containsMatchIn(nonImportOrPackageContent)
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
        val APP_IMPORT_PATTERN = Regex("""(?m)^\s*import\s+com\.lomo\.app(?:\.|$)""")
        val APP_FQCN_PATTERN = Regex("""\bcom\.lomo\.app\.[A-Za-z_]\w*""")
        val IMPORT_OR_PACKAGE_LINE_PATTERN = Regex("""^\s*(import|package)\s+""")
    }
}
