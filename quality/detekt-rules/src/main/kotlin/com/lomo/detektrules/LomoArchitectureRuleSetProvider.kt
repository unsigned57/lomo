package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.lexer.KtTokens
import java.nio.file.Files
import java.nio.file.Path

class LomoArchitectureRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("lomo-architecture")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("AppSourceBoundary") to ::AppSourceBoundaryRule,
                RuleName("AppBuildDependencyBoundary") to ::AppBuildDependencyBoundaryRule,
                RuleName("DomainLayerIsolation") to ::DomainLayerIsolationRule,
                RuleName("DomainPackageShape") to ::DomainPackageShapeRule,
                RuleName("ViewModelBoundary") to ::ViewModelBoundaryRule,
                RuleName("UseCaseLocation") to ::UseCaseLocationRule,
                RuleName("RepositoryImplLocation") to ::RepositoryImplLocationRule,
                RuleName("DaoLocation") to ::DaoLocationRule,
                RuleName("EntityLocation") to ::EntityLocationRule,
                RuleName("ComposableLayer") to ::ComposableLayerRule,
                RuleName("DataRepositoryContract") to ::DataRepositoryContractRule,
                RuleName("DataLayerUiDependency") to ::DataLayerUiDependencyRule,
                RuleName("P0HotspotRepositoryBoundary") to ::P0HotspotRepositoryBoundaryRule,
                RuleName("UiComponentsLayerBoundary") to ::UiComponentsLayerBoundaryRule,
                RuleName("NoSourceSuppressions") to ::NoSourceSuppressionsRule,
                RuleName("NoPlaceholderImplementation") to ::NoPlaceholderImplementationRule,
                RuleName("NoConstantBranchCondition") to ::NoConstantBranchConditionRule,
                RuleName("NoUnreachableBlockTail") to ::NoUnreachableBlockTailRule,
                RuleName("NoRedundantExhaustiveElse") to ::NoRedundantExhaustiveElseRule,
                RuleName("NoCrossFileDuplicateTopLevel") to ::NoCrossFileDuplicateTopLevelRule,
                RuleName("NoUnreferencedTopLevelDeclaration") to ::NoUnreferencedTopLevelDeclarationRule,
            ),
        )
}

private abstract class LomoArchitectureRule(
    config: Config,
    description: String,
) : Rule(config, description) {
    protected fun KtFile.path(): String = getViewProvider().getVirtualFile().getPath().replace('\\', '/')

    protected fun KtFile.isProductionSource(): Boolean = path().contains("/src/main/")

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
        path().substringBefore("/src/main/", missingDelimiterValue = "").ifBlank { null }

    protected fun KtFile.productionSourcePaths(): Set<String> {
        val moduleRoot = moduleRoot() ?: return setOf(path())
        val mainRoot = Path.of(moduleRoot, "src", "main")
        if (!Files.exists(mainRoot)) return setOf(path())
        return Files.walk(mainRoot).use { stream ->
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
}

private class AppSourceBoundaryRule(
    config: Config,
) : LomoArchitectureRule(config, "App and ui-components must not reference data-layer implementations directly.") {
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        val isAppSource = "/app/src/main/java/" in path
        val isUiSource = "/ui-components/src/main/java/" in path
        if (!isAppSource && !isUiSource) return

        val forbidden =
            file.importPaths().firstOrNull { it.startsWith("com.lomo.data.") }
                ?: Regex("""\bcom\.lomo\.data\.[A-Za-z_]\w*""")
                    .find(
                        file.text
                            .lineSequence()
                            .filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }
                            .joinToString("\n"),
                    )?.value

        if (forbidden != null) {
            reportFile(file, "Forbidden app/ui-components reference to data layer: $forbidden")
        }
    }
}

private class AppBuildDependencyBoundaryRule(
    config: Config,
) : LomoArchitectureRule(config, "app/build.gradle.kts must not expose :data outside implementation.") {
    private val forbiddenPatterns =
        listOf(
            Regex("""(?s)\b(?:api|compileOnly|ksp)\s*\(\s*project\s*\(\s*(?:path\s*=\s*)?['"]:data['"][^)]*\)\s*\)"""),
            Regex("""(?s)\b(?:api|compileOnly|ksp)\s*\(\s*projects\.data\s*\)"""),
            Regex("""(?s)\badd\s*\(\s*['"](?:api|compileOnly|ksp)['"]\s*,\s*project\s*\(\s*(?:path\s*=\s*)?['"]:data['"][^)]*\)\s*\)"""),
            Regex("""(?s)\badd\s*\(\s*['"](?:api|compileOnly|ksp)['"]\s*,\s*projects\.data\s*\)"""),
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        if (!path.endsWith("/app/build.gradle.kts")) return

        val offender = forbiddenPatterns.firstOrNull { it.containsMatchIn(file.text) }
        if (offender != null) {
            reportFile(file, "app/build.gradle.kts must not expose :data outside implementation.")
        }
    }
}

private class DomainLayerIsolationRule(
    config: Config,
) : LomoArchitectureRule(config, "domain must stay free of Android, DI, Compose, Room, networking, git, and data-layer imports.") {
    private val forbiddenPrefixes =
        listOf(
            "android.",
            "androidx.compose.",
            "androidx.lifecycle.",
            "androidx.room.",
            "dagger.",
            "javax.inject.",
            "io.ktor.",
            "org.eclipse.jgit.",
            "com.lomo.data.",
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/domain/src/main/java/")) return

        val forbidden =
            file.importPaths().firstOrNull { candidate -> forbiddenPrefixes.any(candidate::startsWith) }
                ?: file.findForbiddenQualifiedReference(forbiddenPrefixes)
        if (forbidden != null) {
            reportFile(file, "Forbidden domain import: $forbidden")
        }
    }
}

private class DomainPackageShapeRule(
    config: Config,
) : LomoArchitectureRule(config, "domain source files must live under model, repository, or usecase.") {
    private val allowedTopLevelPackages = setOf("model", "repository", "usecase")

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        val root = "/domain/src/main/java/com/lomo/domain/"
        if (!path.contains(root)) return

        val relativePath = path.substringAfter(root)
        val topLevelPackage = relativePath.substringBefore('/')
        if (topLevelPackage !in allowedTopLevelPackages) {
            reportFile(file, "Forbidden domain package placement: $relativePath")
        }
    }
}

private class ViewModelBoundaryRule(
    config: Config,
) : LomoArchitectureRule(config, "ViewModel classes must not depend on domain repositories, services, or data-layer details.") {
    private val bannedExactImports =
        setOf(
            "androidx.documentfile.provider.DocumentFile",
            "androidx.room.RoomDatabase",
            "com.lomo.data.git.GitSyncEngine",
            "com.lomo.data.webdav.WebDavClient",
        )
    private val bannedSuffixes = listOf("Dao", "DataSource", "RepositoryImpl")
    private val bannedQualifiedPrefixes = listOf("com.lomo.data.", "com.lomo.domain.repository.")
    private val bannedSimpleTypes = setOf("RoomDatabase", "DocumentFile", "GitSyncEngine", "WebDavClient")

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val file = classOrObject.containingKtFile
        if (!file.path().contains("/app/src/main/java/")) return
        if (!classOrObject.name.orEmpty().endsWith("ViewModel")) return

        val imports = file.importPaths()
        val importedDomainRepositories =
            imports
                .filter { it.startsWith("com.lomo.domain.repository.") }
                .map { it.substringAfterLast('.') }
                .toSet()
        val forbiddenImport =
            imports.firstOrNull { importPath ->
                importPath.startsWith("com.lomo.data.") ||
                    importPath.startsWith("com.lomo.domain.repository.") ||
                    importPath in bannedExactImports ||
                    bannedSuffixes.any { suffix -> importPath.substringAfterLast('.').endsWith(suffix) }
            }
        if (forbiddenImport != null) {
            reportDeclaration(classOrObject, "Forbidden ViewModel dependency: $forbiddenImport")
            return
        }

        val forbiddenConstructorType =
            classOrObject.constructorTypeTexts().firstOrNull { typeText ->
                typeText.contains("com.lomo.data.") ||
                    typeText.contains("com.lomo.domain.repository.") ||
                    typeText.typeIdentifiers().any { identifier ->
                        identifier in importedDomainRepositories ||
                            identifier in bannedSimpleTypes ||
                            bannedSuffixes.any(identifier::endsWith)
                    }
            }
        if (forbiddenConstructorType != null) {
            reportDeclaration(classOrObject, "Forbidden ViewModel constructor dependency: $forbiddenConstructorType")
            return
        }

        val forbiddenQualifiedReference = file.findForbiddenQualifiedReference(bannedQualifiedPrefixes)
        if (forbiddenQualifiedReference != null) {
            reportDeclaration(classOrObject, "Forbidden ViewModel qualified reference: $forbiddenQualifiedReference")
            return
        }

        val forbiddenSimpleReference =
            bannedSimpleTypes.firstOrNull { token -> token in classOrObject.text }
                ?: bannedSuffixes.firstOrNull { suffix -> Regex("""\b[A-Za-z_]\w*$suffix\b""").containsMatchIn(classOrObject.text) }
        if (forbiddenSimpleReference != null) {
            reportDeclaration(classOrObject, "Forbidden ViewModel reference: $forbiddenSimpleReference")
        }
    }
}

private class UseCaseLocationRule(
    config: Config,
) : LomoArchitectureRule(config, "UseCase declarations must live under domain/usecase.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (!classOrObject.name.orEmpty().endsWith("UseCase")) return
        val path = classOrObject.containingKtFile.path()
        if (!path.contains("/domain/src/main/java/com/lomo/domain/usecase/")) {
            reportDeclaration(classOrObject, "UseCase declarations must live under domain/usecase.")
        }
    }
}

private class RepositoryImplLocationRule(
    config: Config,
) : LomoArchitectureRule(config, "RepositoryImpl declarations must live under data/repository.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (!classOrObject.name.orEmpty().endsWith("RepositoryImpl")) return
        val path = classOrObject.containingKtFile.path()
        if (!path.contains("/data/src/main/java/com/lomo/data/repository/")) {
            reportDeclaration(classOrObject, "RepositoryImpl declarations must live under data/repository.")
        }
    }
}

private class DaoLocationRule(
    config: Config,
) : LomoArchitectureRule(config, "Dao declarations must live under data/local/dao.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (!classOrObject.name.orEmpty().endsWith("Dao")) return
        val path = classOrObject.containingKtFile.path()
        if (!path.contains("/data/src/main/java/com/lomo/data/local/dao/")) {
            reportDeclaration(classOrObject, "Dao declarations must live under data/local/dao.")
        }
    }
}

private class EntityLocationRule(
    config: Config,
) : LomoArchitectureRule(config, "Entity declarations must live under data/local/entity.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (!classOrObject.name.orEmpty().endsWith("Entity")) return
        val path = classOrObject.containingKtFile.path()
        if (!path.contains("/data/src/main/java/com/lomo/data/local/entity/")) {
            reportDeclaration(classOrObject, "Entity declarations must live under data/local/entity.")
        }
    }
}

private class ComposableLayerRule(
    config: Config,
) : LomoArchitectureRule(config, "@Composable is only allowed in app and ui-components.") {
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        if (!path.contains("/domain/src/main/java/") && !path.contains("/data/src/main/java/")) return
        if ("@Composable" in file.text) {
            reportFile(file, "@Composable is only allowed in app and ui-components.")
        }
    }
}

private class DataRepositoryContractRule(
    config: Config,
) : LomoArchitectureRule(config, "Every data RepositoryImpl must implement a domain repository interface.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val name = classOrObject.name.orEmpty()
        val file = classOrObject.containingKtFile
        if (!name.endsWith("RepositoryImpl")) return
        if (!file.path().contains("/data/src/main/java/com/lomo/data/repository/")) return

        val importedDomainRepositories =
            file.importPaths()
                .filter { it.startsWith("com.lomo.domain.repository.") }
                .map { it.substringAfterLast('.') }
        val superTypeText = classOrObject.superTypeListEntries.joinToString(" ") { it.text }
        if (importedDomainRepositories.none { repoName -> Regex("""\b$repoName\b""").containsMatchIn(superTypeText) }) {
            reportDeclaration(classOrObject, "RepositoryImpl must implement at least one domain.repository interface.")
        }
    }
}

private class DataLayerUiDependencyRule(
    config: Config,
) : LomoArchitectureRule(config, "data must stay free of app-layer and UI-layer dependencies.") {
    private val forbiddenPrefixes =
        listOf(
            "com.lomo.app.",
            "com.lomo.ui.",
            "androidx.compose.",
            "androidx.lifecycle.",
        )
    private val forbiddenSimpleTokens = setOf("ViewModel", "UiState")

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/data/src/main/java/")) return

        val forbiddenImport =
            file.importPaths().firstOrNull { candidate -> forbiddenPrefixes.any(candidate::startsWith) }
        if (forbiddenImport != null) {
            reportFile(file, "Forbidden data-layer UI dependency: $forbiddenImport")
            return
        }

        val forbiddenQualifiedReference = file.findForbiddenQualifiedReference(forbiddenPrefixes)
        if (forbiddenQualifiedReference != null) {
            reportFile(file, "Forbidden data-layer qualified UI dependency: $forbiddenQualifiedReference")
            return
        }

        val forbiddenSimpleToken =
            forbiddenSimpleTokens.firstOrNull { token -> Regex("""\b${Regex.escape(token)}\b""").containsMatchIn(file.bodyText()) }
        if (forbiddenSimpleToken != null) {
            reportFile(file, "Forbidden data-layer UI token reference: $forbiddenSimpleToken")
        }
    }
}

private class P0HotspotRepositoryBoundaryRule(
    config: Config,
) : LomoArchitectureRule(config, "P0 hotspot files must not import domain repositories directly.") {
    private val hotspotSuffixes =
        setOf(
            "/app/src/main/java/com/lomo/app/feature/settings/SettingsGitCoordinator.kt",
            "/app/src/main/java/com/lomo/app/feature/settings/SettingsWebDavCoordinator.kt",
            "/app/src/main/java/com/lomo/app/feature/main/MainStartupCoordinator.kt",
            "/app/src/main/java/com/lomo/app/feature/main/MainVersionHistoryCoordinator.kt",
            "/app/src/main/java/com/lomo/app/feature/memo/MemoEditorViewModel.kt",
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        if (hotspotSuffixes.none(path::endsWith)) return
        val forbidden =
            file.importPaths().firstOrNull { it.startsWith("com.lomo.domain.repository.") }
                ?: file.findForbiddenQualifiedReference(listOf("com.lomo.domain.repository."))
        if (forbidden != null) {
            reportFile(file, "P0 hotspot file must not import domain repositories directly: $forbidden")
        }
    }
}

private class UiComponentsLayerBoundaryRule(
    config: Config,
) : LomoArchitectureRule(config, "ui-components must stay free of app, data, and domain command dependencies.") {
    private val forbiddenPrefixes =
        listOf(
            "com.lomo.app.",
            "com.lomo.data.",
            "com.lomo.domain.repository.",
            "com.lomo.domain.usecase.",
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/ui-components/src/main/java/")) return

        val forbidden =
            file.importPaths().firstOrNull { candidate -> forbiddenPrefixes.any(candidate::startsWith) }
                ?: file.findForbiddenQualifiedReference(forbiddenPrefixes)
        if (forbidden != null) {
            reportFile(file, "Forbidden ui-components layer dependency: $forbidden")
        }
    }
}

private class NoSourceSuppressionsRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not use @Suppress to bypass static checks.") {
    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        val file = annotationEntry.containingKtFile
        if (!file.path().contains("/src/main/")) return
        if (file.isPathExcluded()) return

        val shortName = annotationEntry.shortName?.asString()
        if (shortName == "Suppress" || shortName == "SuppressWarnings") {
            report(
                Finding(
                    Entity.from(annotationEntry),
                    "Do not use @Suppress in production source. Refactor the code or tighten Detekt centrally.",
                ),
            )
        }
    }
}

private class NoPlaceholderImplementationRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not commit placeholder implementations like TODO() or NotImplementedError().") {
    private val placeholderPatterns =
        listOf(
            Regex("""\bTODO\s*\("""),
            Regex("""\bNotImplementedError\s*\("""),
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.isProductionSource()) return

        val placeholder = placeholderPatterns.firstOrNull { it.containsMatchIn(file.bodyText()) }
        if (placeholder != null) {
            reportFile(file, "Placeholder implementation detected. Replace TODO()/NotImplementedError() with real logic.")
        }
    }
}

private class NoConstantBranchConditionRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not commit branches whose conditions are already constant.") {
    override fun visitIfExpression(expression: KtIfExpression) {
        super.visitIfExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return

        val condition = expression.condition ?: return
        val constant = condition.evaluateBooleanConstant() ?: return
        reportElement(
            condition,
            "Branch condition is always $constant. Remove the dead branch or make the condition depend on runtime state.",
        )
    }

    override fun visitWhileExpression(expression: KtWhileExpression) {
        super.visitWhileExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return

        val condition = expression.condition ?: return
        val constant = condition.evaluateBooleanConstant() ?: return
        if (constant) return
        reportElement(
            condition,
            "Loop condition is always $constant. Remove the dead loop or replace it with explicit runtime control flow.",
        )
    }

    override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
        super.visitDoWhileExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return

        val condition = expression.condition ?: return
        val constant = condition.evaluateBooleanConstant() ?: return
        if (constant) return
        reportElement(
            condition,
            "Loop condition is always $constant. Remove the dead loop or replace it with explicit runtime control flow.",
        )
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return
        if (expression.subjectExpression != null) return

        expression.entries.forEach { entry ->
            val constant = entry.singleBooleanCondition() ?: return@forEach
            reportElement(
                entry.expression ?: expression,
                "when branch condition is always $constant. Remove the dead branch or make the guard depend on runtime state.",
            )
        }
    }
}

private class NoUnreachableBlockTailRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not keep statements after an unconditional control-transfer expression.") {
    override fun visitBlockExpression(expression: KtBlockExpression) {
        super.visitBlockExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return

        var terminatorSeen = false
        expression.statements.forEach { statement ->
            if (terminatorSeen) {
                reportElement(
                    statement,
                    "Unreachable statement after unconditional control transfer. Remove dead code after return/throw/break/continue.",
                )
                return
            }

            if (statement.isUnconditionalJump()) {
                terminatorSeen = true
            }
        }
    }
}

private class NoRedundantExhaustiveElseRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not keep an else branch when a Boolean when is already exhaustive.") {
    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return
        if (expression.subjectExpression == null) return

        val elseEntry = expression.entries.firstOrNull(KtWhenEntry::isElse) ?: return
        val booleanConditions =
            expression.entries
                .filterNot(KtWhenEntry::isElse)
                .mapNotNull(KtWhenEntry::singleBooleanCondition)
                .toSet()

        if (booleanConditions == setOf(true, false)) {
            report(
                Finding(
                    Entity.from(elseEntry),
                    "Redundant else branch in exhaustive Boolean when. Remove else and keep explicit true/false branches only.",
                ),
            )
        }
    }
}

private class NoCrossFileDuplicateTopLevelRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not duplicate the same top-level helper signature across files in one module.") {
    private val ignoreAnnotated = config.valueOrDefault("ignoreAnnotated", listOf("Composable", "Preview")).toSet()
    private val ignoreOperator = config.valueOrDefault("ignoreOperator", true)
    private val seenByModule = mutableMapOf<String, MutableMap<TopLevelFunctionSignature, KtNamedFunction>>()

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return
        if (!function.isTopLevel) return
        if (function.shouldIgnoreTopLevelDuplicate(ignoreAnnotated, ignoreOperator)) return

        val moduleRoot = file.moduleRoot() ?: return
        val signature = function.topLevelSignature()
        val seen = seenByModule.getOrPut(moduleRoot) { mutableMapOf() }
        val firstDeclaration = seen[signature]

        if (firstDeclaration == null) {
            seen[signature] = function
            return
        }

        if (firstDeclaration.containingKtFile.path() == file.path()) return

        val firstRelativePath = firstDeclaration.containingKtFile.path().removePrefix("$moduleRoot/")
        reportNamedDeclaration(
            function,
            "Duplicate top-level declaration '${signature.render()}'. First declaration: $firstRelativePath. Reuse or extract shared logic instead of redefining it.",
        )
    }
}

private class NoUnreferencedTopLevelDeclarationRule(
    config: Config,
) : LomoArchitectureRule(config, "Production source must not keep non-public top-level declarations with no reachable in-module references.") {
    private val ignoreAnnotated =
        config.valueOrDefault(
            "ignoreAnnotated",
            listOf("VisibleForTesting", "Keep", "Preview", "Module", "Provides", "Binds", "Multibinds", "HiltViewModel"),
        ).toSet()
    private val moduleStates = mutableMapOf<String, ModuleDeadDeclarationState>()

    override fun visitKtFile(file: KtFile) {
        if (!file.isProductionSource()) {
            super.visitKtFile(file)
            return
        }

        val moduleRoot = file.moduleRoot() ?: return
        val state =
            moduleStates.getOrPut(moduleRoot) {
                ModuleDeadDeclarationState(expectedFiles = file.productionSourcePaths())
            }

        super.visitKtFile(file)

        state.visitedFiles += file.path()
        if (!state.flushed && state.visitedFiles.containsAll(state.expectedFiles)) {
            flushFindings(state)
            state.flushed = true
        }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return
        if (!function.isTopLevel) return
        if (!function.isNonPublicTopLevelDeclaration(ignoreAnnotated)) return

        val moduleRoot = file.moduleRoot() ?: return
        moduleStates
            .getOrPut(moduleRoot) { ModuleDeadDeclarationState(expectedFiles = file.productionSourcePaths()) }
            .declarations += TrackedTopLevelDeclaration(TopLevelDeclarationKey.Function(function.name.orEmpty(), function.valueParameters.size), function)
    }

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val file = classOrObject.containingKtFile
        if (!file.isProductionSource()) return
        if (!classOrObject.isTopLevel()) return
        if (!classOrObject.isNonPublicTopLevelDeclaration(ignoreAnnotated)) return

        val moduleRoot = file.moduleRoot() ?: return
        moduleStates
            .getOrPut(moduleRoot) { ModuleDeadDeclarationState(expectedFiles = file.productionSourcePaths()) }
            .declarations += TrackedTopLevelDeclaration(TopLevelDeclarationKey.Type(classOrObject.name.orEmpty()), classOrObject)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val calleeName = expression.calleeExpression?.text?.substringAfterLast('.')?.takeIf(String::isNotBlank) ?: return
        val moduleRoot = file.moduleRoot() ?: return
        moduleStates
            .getOrPut(moduleRoot) { ModuleDeadDeclarationState(expectedFiles = file.productionSourcePaths()) }
            .functionReferences += TopLevelDeclarationKey.Function(calleeName, expression.valueArguments.size)
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        super.visitSimpleNameExpression(expression)
        val nameReference = expression as? KtNameReferenceExpression ?: return
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        if (nameReference.isDeclarationNameReference()) return
        if (nameReference.parent is KtPackageDirective) return

        val moduleRoot = file.moduleRoot() ?: return
        moduleStates
            .getOrPut(moduleRoot) { ModuleDeadDeclarationState(expectedFiles = file.productionSourcePaths()) }
            .typeReferences += TopLevelDeclarationKey.Type(nameReference.getReferencedName())
    }

    private fun flushFindings(state: ModuleDeadDeclarationState) {
        val referencedKeys = state.functionReferences + state.typeReferences
        state.declarations
            .filterNot { tracked -> tracked.key in referencedKeys }
            .forEach { tracked ->
                reportNamedDeclaration(
                    tracked.declaration,
                    "Unreferenced top-level declaration '${tracked.declaration.name.orEmpty()}'. Remove dead code or make the declaration reachable. Public declarations are intentionally out of scope for this rule.",
                )
            }
    }
}

private fun KtExpression.evaluateBooleanConstant(): Boolean? =
    when (this) {
        is KtParenthesizedExpression -> expression?.evaluateBooleanConstant()
        is KtConstantExpression ->
            when (text) {
                "true" -> true
                "false" -> false
                else -> null
            }
        is KtPrefixExpression ->
            when (operationToken) {
                KtTokens.EXCL -> baseExpression?.evaluateBooleanConstant()?.not()
                else -> null
            }
        is KtBinaryExpression -> {
            val leftValue = left?.evaluateBooleanConstant()
            val rightValue = right?.evaluateBooleanConstant()
            when (operationToken) {
                KtTokens.ANDAND -> if (leftValue != null && rightValue != null) leftValue && rightValue else null
                KtTokens.OROR -> if (leftValue != null && rightValue != null) leftValue || rightValue else null
                KtTokens.EQEQ -> if (leftValue != null && rightValue != null) leftValue == rightValue else null
                KtTokens.EXCLEQ -> if (leftValue != null && rightValue != null) leftValue != rightValue else null
                else -> null
            }
        }
        else -> null
    }

private fun KtWhenEntry.singleBooleanCondition(): Boolean? {
    if (isElse || conditions.size != 1) return null
    val expression = (conditions.single() as? KtWhenConditionWithExpression)?.expression ?: return null
    return expression.evaluateBooleanConstant()
}

private fun KtExpression.isUnconditionalJump(): Boolean =
    this is KtReturnExpression ||
        this is KtThrowExpression ||
        this is KtBreakExpression ||
        this is KtContinueExpression

private data class TopLevelFunctionSignature(
    val name: String,
    val receiverType: String?,
    val parameterTypes: List<String>,
    val returnType: String?,
) {
    fun render(): String {
        val receiverPrefix = receiverType?.let { "$it." }.orEmpty()
        val returnSuffix = returnType?.let { ": $it" }.orEmpty()
        return "$receiverPrefix$name(${parameterTypes.joinToString(", ")}$returnSuffix)"
    }
}

private sealed interface TopLevelDeclarationKey {
    data class Function(
        val name: String,
        val arity: Int,
    ) : TopLevelDeclarationKey

    data class Type(
        val name: String,
    ) : TopLevelDeclarationKey
}

private data class TrackedTopLevelDeclaration(
    val key: TopLevelDeclarationKey,
    val declaration: KtNamedDeclaration,
)

private data class ModuleDeadDeclarationState(
    val expectedFiles: Set<String>,
    val visitedFiles: MutableSet<String> = mutableSetOf(),
    val declarations: MutableList<TrackedTopLevelDeclaration> = mutableListOf(),
    val functionReferences: MutableSet<TopLevelDeclarationKey.Function> = mutableSetOf(),
    val typeReferences: MutableSet<TopLevelDeclarationKey.Type> = mutableSetOf(),
    var flushed: Boolean = false,
)

private val builtInTypeFqNames =
    mapOf(
        "Any" to "kotlin.Any",
        "Boolean" to "kotlin.Boolean",
        "Byte" to "kotlin.Byte",
        "Char" to "kotlin.Char",
        "Double" to "kotlin.Double",
        "Float" to "kotlin.Float",
        "Int" to "kotlin.Int",
        "Long" to "kotlin.Long",
        "Short" to "kotlin.Short",
        "String" to "kotlin.String",
        "Unit" to "kotlin.Unit",
        "List" to "kotlin.collections.List",
        "MutableList" to "kotlin.collections.MutableList",
        "Set" to "kotlin.collections.Set",
        "MutableSet" to "kotlin.collections.MutableSet",
        "Map" to "kotlin.collections.Map",
        "MutableMap" to "kotlin.collections.MutableMap",
        "Result" to "kotlin.Result",
    )

private val typeTokenPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
private val typeTokenKeywords = setOf("in", "out", "reified", "suspend")

private fun KtNamedFunction.shouldIgnoreTopLevelDuplicate(
    ignoreAnnotated: Set<String>,
    ignoreOperator: Boolean,
): Boolean =
    hasAnyAnnotation(ignoreAnnotated) || (ignoreOperator && hasModifier(KtTokens.OPERATOR_KEYWORD))

private fun KtNamedFunction.topLevelSignature(): TopLevelFunctionSignature {
    val file = containingKtFile
    return TopLevelFunctionSignature(
        name = name.orEmpty(),
        receiverType = receiverTypeReference?.canonicalTypeText(file),
        parameterTypes = valueParameters.map { parameter -> parameter.typeReference?.canonicalTypeText(file) ?: "<implicit>" },
        returnType = typeReference?.canonicalTypeText(file) ?: "<inferred>",
    )
}

private fun KtNamedDeclaration.hasAnyAnnotation(annotationNames: Set<String>): Boolean =
    annotationEntries.any { entry -> entry.shortName?.asString() in annotationNames }

private fun KtNamedFunction.isNonPublicTopLevelDeclaration(ignoreAnnotated: Set<String>): Boolean =
    (hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.INTERNAL_KEYWORD)) && !hasAnyAnnotation(ignoreAnnotated)

private fun KtClassOrObject.isNonPublicTopLevelDeclaration(ignoreAnnotated: Set<String>): Boolean =
    (hasModifier(KtTokens.PRIVATE_KEYWORD) || hasModifier(KtTokens.INTERNAL_KEYWORD)) && !hasAnyAnnotation(ignoreAnnotated)

private fun KtNameReferenceExpression.isDeclarationNameReference(): Boolean {
    val declarationParent = parent as? KtNamedDeclaration ?: return false
    return declarationParent.nameIdentifier == this
}

private fun org.jetbrains.kotlin.psi.KtTypeReference.canonicalTypeText(file: KtFile): String =
    text
        .replace("\\s+".toRegex(), "")
        .replace(typeTokenPattern) { match ->
            val token = match.value
            when {
                token in typeTokenKeywords -> token
                '.' in token -> token
                else -> file.importDirectives
                    .firstNotNullOfOrNull { directive ->
                        val importedFqName = directive.importedFqName?.asString() ?: return@firstNotNullOfOrNull null
                        val localName = directive.aliasName ?: importedFqName.substringAfterLast('.')
                        importedFqName.takeIf { localName == token }
                    }
                    ?: builtInTypeFqNames[token]
                    ?: token
            }
        }
