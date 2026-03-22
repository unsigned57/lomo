package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

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
            ),
        )
}

private abstract class LomoArchitectureRule(
    config: Config,
    description: String,
) : Rule(config, description) {
    protected fun KtFile.path(): String = getViewProvider().getVirtualFile().getPath().replace('\\', '/')

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

    protected fun reportDeclaration(
        declaration: KtClassOrObject,
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
