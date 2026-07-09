package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Files
import java.nio.file.Path

internal abstract class LomoBaseRule(
    config: Config,
    description: String,
) : Rule(config, description) {
    protected fun KtFile.path(): String = getViewProvider().getVirtualFile().getPath().replace('\\', '/')

    protected fun KtFile.isProductionSource(): Boolean {
        val normalizedPath = path()
        return normalizedPath.contains("/src/") &&
            !normalizedPath.contains("/test/") &&
            !normalizedPath.contains("/test@android/") &&
            !normalizedPath.contains("/src/test/")
    }

    protected fun KtFile.isTestFile(): Boolean {
        val normalizedPath = path()
        return normalizedPath.contains("/test/") || normalizedPath.contains("/test@android/")
    }

    protected fun KtFile.isPathExcluded(): Boolean =
        config.valueOrDefault("excludes", emptyList<String>()).any { exclusion ->
            val normalizedExclusion = exclusion.replace('\\', '/')
            path().endsWith(normalizedExclusion) || path().contains(normalizedExclusion)
        }

    protected fun KtFile.bodyText(): String =
        text
            .lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("package ") || trimmed.startsWith("import ")
            }.joinToString("\n")

    protected fun KtFile.importPaths(): List<String> =
        text
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ").trim() }
            .toList()

    protected fun KtFile.findForbiddenQualifiedReference(prefixes: List<String>): String? =
        prefixes.firstOrNull { prefix ->
            Regex("""\b${Regex.escape(prefix)}[A-Za-z_]\w*""").containsMatchIn(bodyText())
        }

    protected fun KtFile.moduleRoot(): String? =
        path().substringBefore("/src/", missingDelimiterValue = "").ifBlank { null }

    protected fun KtFile.productionSourcePaths(): Set<String> {
        val moduleRoot = moduleRoot() ?: return setOf(path())
        val sourceRoot = Path.of(moduleRoot, "src")
        if (!Files.exists(sourceRoot)) return setOf(path())
        return Files.walk(sourceRoot).use { stream ->
            stream
                .filter { candidate -> Files.isRegularFile(candidate) }
                .map { candidate -> candidate.toString().replace('\\', '/') }
                .filter { candidate -> candidate.endsWith(".kt") || candidate.endsWith(".kts") }
                .toList()
                .toSet()
        }
    }

    protected fun KtClassOrObject.constructorTypeTexts(): List<String> =
        (this as? KtClass)
            ?.getPrimaryConstructorParameters()
            ?.mapNotNull { it.getTypeReference()?.text?.trim() }
            .orEmpty()

    protected fun String.typeIdentifiers(): Set<String> =
        Regex("""[A-Za-z_][A-Za-z0-9_]*""")
            .findAll(this)
            .map { it.value }
            .toSet()

    protected fun reportFile(file: KtFile, message: String) {
        report(Finding(Entity.from(file), message))
    }

    protected fun reportElement(
        expression: KtExpression,
        message: String,
    ) {
        report(Finding(Entity.from(expression), message))
    }

    protected fun reportDeclaration(
        declaration: KtClassOrObject,
        message: String,
    ) {
        report(Finding(Entity.from(declaration), message))
    }

    protected fun reportNamedDeclaration(
        declaration: KtNamedDeclaration,
        message: String,
    ) {
        report(Finding(Entity.from(declaration), message))
    }

    protected fun org.jetbrains.kotlin.psi.KtCallExpression.getMockedTypeText(): String? {
        // 1. Explicit type argument: mockk<MyRepo>()
        val typeArgText = typeArguments.singleOrNull()?.typeReference?.text?.trim()
        if (typeArgText != null) return typeArgText

        // 2. Property initialization: val repo: MyRepo = mockk()
        val property = parent as? org.jetbrains.kotlin.psi.KtProperty
        val propertyTypeText = property?.typeReference?.text?.trim()
        if (propertyTypeText != null) return propertyTypeText

        // 3. Assignment to a property (common for lateinit var): repo = mockk()
        val binaryExpr = parent as? org.jetbrains.kotlin.psi.KtBinaryExpression
        if (binaryExpr?.operationReference?.text == "=") {
            val leftSide = binaryExpr.left?.text?.trim()
            if (leftSide != null) {
                // Heuristic: look for the declaration of this property in the same class
                var current = parent
                while (current != null && current !is org.jetbrains.kotlin.psi.KtClass) {
                    current = current.parent
                }
                val klass = if (current is org.jetbrains.kotlin.psi.KtClass) current else null
                val declarations = klass?.body?.declarations
                val declaration = declarations?.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>()
                    ?.firstOrNull { it.name == leftSide }
                return declaration?.typeReference?.text?.trim()
            }
        }
        return null
    }
}
