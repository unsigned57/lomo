package com.lomo.domain.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPurityTest {
    @Test
    fun `domain does not depend on inject annotations`() {
        val sourceRoot = File("src/main/java")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders =
            kotlinFiles.filter { file ->
                val text = file.readText()
                text.contains("@Inject") || text.contains("javax.inject.Inject")
            }

        assertTrue(
            "Domain layer must stay framework-agnostic. Offenders: ${offenders.joinToString { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `domain source only uses model repository and usecase categories`() {
        val sourceRoot = File("src/main/java/com/lomo/domain")
        val allowedTopLevel = setOf("model", "repository", "usecase")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders =
            kotlinFiles.filter { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                val topLevel = relative.substringBefore('/')
                topLevel !in allowedTopLevel
            }

        assertTrue(
            "Domain source categories must be model/repository/usecase. Offenders: " +
                offenders.joinToString { it.path },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `domain repository package only declares interfaces`() {
        val sourceRoot = File("src/main/java/com/lomo/domain/repository")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders =
            kotlinFiles.filter { file ->
                val declarations =
                    file
                        .readLines()
                        .filter { line -> line.trimStart().matches(TOP_LEVEL_DECLARATION_PATTERN) }
                declarations.any { line -> !line.contains("interface ") }
            }

        assertTrue(
            "Domain repository contracts must be interfaces only. Offenders: " +
                offenders.joinToString { it.path },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `domain source does not keep compatibility typealias`() {
        val sourceRoot = File("src/main/java/com/lomo/domain")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders =
            kotlinFiles.filter { file ->
                file
                    .readLines()
                    .any { line -> line.trimStart().startsWith("typealias ") }
            }

        assertTrue(
            "Domain source must not declare typealias compatibility shims. Offenders: " +
                offenders.joinToString { it.path },
            offenders.isEmpty(),
        )
    }

    private companion object {
        val TOP_LEVEL_DECLARATION_PATTERN =
            Regex(
                """^(?:public\s+|internal\s+|private\s+)?(?:sealed\s+)?(?:data\s+)?(?:class|object|interface|enum\s+class)\b.*""",
            )
    }
}
