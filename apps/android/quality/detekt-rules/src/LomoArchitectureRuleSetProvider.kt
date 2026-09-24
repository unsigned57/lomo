package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.RuleName
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.lexer.KtTokens

class LomoArchitectureRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("lomo-architecture")

    override fun instance(): RuleSet =
        RuleSet(
            ruleSetId,
            mapOf(
                RuleName("AppSourceBoundary") to ::AppSourceBoundaryRule,
                RuleName("AppManifestBoundary") to ::AppManifestBoundaryRule,
                RuleName("DomainLayerIsolation") to ::DomainLayerIsolationRule,
                RuleName("DomainPackageShape") to ::DomainPackageShapeRule,
                RuleName("ViewModelBoundary") to ::ViewModelBoundaryRule,
                RuleName("UseCaseLocation") to ::UseCaseLocationRule,
                RuleName("RepositoryImplLocation") to ::RepositoryImplLocationRule,
                RuleName("ComposableLayer") to ::ComposableLayerRule,
                RuleName("DataRepositoryContract") to ::DataRepositoryContractRule,
                RuleName("DataLayerUiDependency") to ::DataLayerUiDependencyRule,
                RuleName("UiComponentsLayerBoundary") to ::UiComponentsLayerBoundaryRule,
                RuleName("UiComponentDesignTokenUsage") to ::UiComponentDesignTokenUsageRule,
                RuleName("NoSourceSuppressions") to ::NoSourceSuppressionsRule,
                RuleName("NoHandwrittenNativeDeclaration") to ::NoHandwrittenNativeDeclarationRule,
                RuleName("NoMutableFlowExposure") to ::NoMutableFlowExposureRule,
                RuleName("NoInferredMutableFlowExposure") to ::NoInferredMutableFlowExposureRule,
                RuleName("ResolvedCallGraph") to ::ResolvedCallGraphRule,
                RuleName("NoPlaceholderImplementation") to ::NoPlaceholderImplementationRule,
                RuleName("NoConstantBranchCondition") to ::NoConstantBranchConditionRule,
                RuleName("NoUnreachableBlockTail") to ::NoUnreachableBlockTailRule,
                RuleName("NoRedundantExhaustiveElse") to ::NoRedundantExhaustiveElseRule,
                RuleName("NoCrossFileDuplicateTopLevel") to ::NoCrossFileDuplicateTopLevelRule,
                RuleName("NoUnreferencedTopLevelDeclaration") to ::NoUnreferencedTopLevelDeclarationRule,
                RuleName("NoSwallowedResult") to ::NoSwallowedResultRule,
                RuleName("NoDeprecatedKept") to ::NoDeprecatedKeptRule,
                RuleName("NoHardcodedVisibleText") to ::NoHardcodedVisibleTextRule,
                RuleName("NoSwallowedCancellationInPagingSource") to ::NoSwallowedCancellationInPagingSourceRule,
                RuleName("NoLoopBoundaryIo") to ::NoLoopBoundaryIoRule,
                RuleName("NoNestedCollectionScan") to ::NoNestedCollectionScanRule,
                RuleName("NoFullRebuildInLocalMutation") to ::NoFullRebuildInLocalMutationRule,
                RuleName("NoSwallowedCancellationInSuspend") to ::NoSwallowedCancellationInSuspendRule,
                RuleName("NoUnboundedFlowSharing") to ::NoUnboundedFlowSharingRule,
                RuleName("NoUnmanagedCoroutineScope") to ::NoUnmanagedCoroutineScopeRule,
                RuleName("NoSilentCatchFallback") to ::NoSilentCatchFallbackRule,
                RuleName("NoSwallowedThrowable") to ::NoSwallowedThrowableRule,
                RuleName("NoStatefulRepositoryOrUseCase") to ::NoStatefulRepositoryOrUseCaseRule,
                RuleName("NoAdHocMemoryCache") to ::NoAdHocMemoryCacheRule,
                RuleName("NoUnconfinedIoOrNative") to ::NoUnconfinedIoOrNativeRule,
                RuleName("NoUnpaginatedFullLoad") to ::NoUnpaginatedFullLoadRule,
                RuleName("ViewModelSingleStateFlow") to ::ViewModelSingleStateFlowRule,
                RuleName("NoInSituRevisionBypass") to ::NoInSituRevisionBypassRule,
                RuleName("NoEventInStateFlow") to ::NoEventInStateFlowRule,
                RuleName("NoMultipleEffectChannels") to ::NoMultipleEffectChannelsRule,
                RuleName("PagingDataCachedIn") to ::PagingDataCachedInRule,
                RuleName("NoWriteOnlyStateFlow") to ::NoWriteOnlyStateFlowRule,
                RuleName("NoCollaboratorDefaultArg") to ::NoCollaboratorDefaultArgRule,
                RuleName("NoMintedIdentity") to ::NoMintedIdentityRule,
                RuleName("NoErrorMessageControlFlow") to ::NoErrorMessageControlFlowRule,
                RuleName("NoPlaceholderCollaborator") to ::NoPlaceholderCollaboratorRule,
                RuleName("NoCapabilitySeam") to ::NoCapabilitySeamRule,
                RuleName("NoSecretInWorkPayload") to ::NoSecretInWorkPayloadRule,
                RuleName("NoNamePredicateDelete") to ::NoNamePredicateDeleteRule,
                RuleName("NoCorruptionEmptyReset") to ::NoCorruptionEmptyResetRule,
                RuleName("NoDomainClock") to ::NoDomainClockRule,
                RuleName("NoConstantStatusValue") to ::NoConstantStatusValueRule,
                RuleName("NoMutableStatePayload") to ::NoMutableStatePayloadRule,
                RuleName("NoWriteInFlowDerivation") to ::NoWriteInFlowDerivationRule,
                RuleName("NoInferredMutableStatePayload") to ::NoInferredMutableStatePayloadRule,
            ),
        )
}

private class AppSourceBoundaryRule(
    config: Config,
) : LomoBaseRule(config, "App and ui-components must not reference data implementations or generated native bindings.") {
    private val forbiddenPrefixes = listOf("com.lomo.data.", "com.lomo.nativebridge.")
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        val isAppSource = "/app/src/" in path
        val isUiSource = "/ui-components/src/" in path
        if (!isAppSource && !isUiSource) return

        val forbidden =
            file.importPaths().firstOrNull { candidate -> forbiddenPrefixes.any(candidate::startsWith) }
                ?: file.findForbiddenQualifiedReference(forbiddenPrefixes)

        if (forbidden != null) {
            reportFile(file, "Forbidden app/ui-components reference to data or native bindings: $forbidden")
        }
    }
}

private class DomainLayerIsolationRule(
    config: Config,
) : LomoBaseRule(config, "domain must stay free of Android, DI, Compose, Room, networking, git, and data-layer imports.") {
    private val forbiddenPrefixes =
        listOf(
            "android.",
            "androidx.compose.",
            "androidx.lifecycle.",
            "androidx.room.",
            "dagger.",
            "javax.inject.",
            "org.koin.",
            "io.ktor.",
            "org.eclipse.jgit.",
            "com.lomo.data.",
            "com.lomo.nativebridge.",
            "com.lomo.app.",
            "com.lomo.ui.",
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/domain/src/")) return

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
) : LomoBaseRule(config, "domain source files must live under model, repository, or usecase.") {
    private val allowedTopLevelPackages = setOf("model", "repository", "usecase")

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        // Kotlin-style layout omits common root package: domain/src/{model,repository,usecase}/...
        val root = "/domain/src/"
        if (!path.contains(root) || path.contains("/domain/test/")) return

        val relativePath = path.substringAfter(root)
        val topLevelPackage = relativePath.substringBefore('/')
        if (topLevelPackage !in allowedTopLevelPackages) {
            reportFile(file, "Forbidden domain package placement: $relativePath")
        }
    }
}

private class ViewModelBoundaryRule(
    config: Config,
) : LomoBaseRule(config, "ViewModel classes must not depend on data-layer implementations or domain repositories; they consume use cases and state holders.") {
    private val bannedSuffixes = listOf("Dao", "DataSource", "RepositoryImpl")
    private val bannedQualifiedPrefixes = listOf("com.lomo.data.", "com.lomo.domain.repository.")

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val file = classOrObject.containingKtFile
        if (!file.path().contains("/app/src/")) return
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
            bannedSuffixes.firstOrNull { suffix -> Regex("""\b[A-Za-z_]\w*$suffix\b""").containsMatchIn(classOrObject.text) }
        if (forbiddenSimpleReference != null) {
            reportDeclaration(classOrObject, "Forbidden ViewModel reference: $forbiddenSimpleReference")
        }
    }
}

private class UseCaseLocationRule(
    config: Config,
) : LomoBaseRule(config, "UseCase declarations must live under domain/usecase.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (!classOrObject.name.orEmpty().endsWith("UseCase")) return
        val path = classOrObject.containingKtFile.path()
        if (!path.contains("/domain/src/usecase/")) {
            reportDeclaration(classOrObject, "UseCase declarations must live under domain/usecase.")
        }
    }
}

private class RepositoryImplLocationRule(
    config: Config,
) : LomoBaseRule(config, "RepositoryImpl declarations must live under data/repository.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        if (!classOrObject.name.orEmpty().endsWith("RepositoryImpl")) return
        val path = classOrObject.containingKtFile.path()
        if (!path.contains("/data/src/repository/")) {
            reportDeclaration(classOrObject, "RepositoryImpl declarations must live under data/repository.")
        }
    }
}

private class ComposableLayerRule(
    config: Config,
) : LomoBaseRule(config, "@Composable is only allowed in app and ui-components.") {
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        val path = file.path()
        if (!path.contains("/domain/src/") && !path.contains("/data/src/")) return
        if ("@Composable" in file.text) {
            reportFile(file, "@Composable is only allowed in app and ui-components.")
        }
    }
}

private class DataRepositoryContractRule(
    config: Config,
) : LomoBaseRule(config, "Every data RepositoryImpl must implement a domain repository interface.") {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val name = classOrObject.name.orEmpty()
        val file = classOrObject.containingKtFile
        if (!name.endsWith("RepositoryImpl")) return
        if (!file.path().contains("/data/src/repository/")) return

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
) : LomoBaseRule(config, "data must stay free of app-layer and UI-layer dependencies.") {
    private val forbiddenPrefixes =
        listOf(
            "com.lomo.app.",
            "com.lomo.ui.",
            "androidx.compose.",
            "androidx.lifecycle.",
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/data/src/")) return

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

    }
}

private class UiComponentsLayerBoundaryRule(
    config: Config,
) : LomoBaseRule(config, "ui-components must stay free of app, data, and domain command dependencies.") {
    private val forbiddenPrefixes =
        listOf(
            "com.lomo.app.",
            "com.lomo.data.",
            "com.lomo.nativebridge.",
            "com.lomo.domain.repository.",
            "com.lomo.domain.usecase.",
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/ui-components/src/")) return

        val forbidden =
            file.importPaths().firstOrNull { candidate -> forbiddenPrefixes.any(candidate::startsWith) }
                ?: file.findForbiddenQualifiedReference(forbiddenPrefixes)
        if (forbidden != null) {
            reportFile(file, "Forbidden ui-components layer dependency: $forbidden")
        }
    }
}

private class UiComponentDesignTokenUsageRule(
    config: Config,
) : LomoBaseRule(
    config,
    "ui-components component source must use semantic component tokens for static shapes and alpha ramps.",
) {
    private val tokenFileSuffixes = config.valueOrDefault("tokenFileSuffixes", listOf("Tokens.kt", "Token.kt"))

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isUiComponentProductionSource()) return
        if (file.isComponentTokenFile(tokenFileSuffixes)) return

        when (expression.calleeExpression?.text?.substringAfterLast('.')) {
            "RoundedCornerShape" -> {
                if (expression.hasStaticShapeArgument()) {
                    reportElement(
                        expression,
                        "Use a semantic component token or AppShapes token instead of creating RoundedCornerShape in component source.",
                    )
                }
            }

            "copy" -> {
                if (expression.hasStaticAlphaArgument()) {
                    reportElement(
                        expression,
                        "Use a semantic component token color helper instead of applying a local alpha ramp in component source.",
                    )
                }
            }
        }
    }

    private fun KtFile.isUiComponentProductionSource(): Boolean =
        isProductionSource() && path().contains("/ui-components/src/component/")

    private fun KtFile.isComponentTokenFile(tokenFileSuffixes: List<String>): Boolean =
        tokenFileSuffixes.any { suffix -> path().endsWith(suffix) }

    private fun KtCallExpression.hasStaticShapeArgument(): Boolean {
        val argumentText = valueArguments.joinToString(separator = " ") { argument -> argument.text }
        return ".dp" in argumentText ||
            "AppSpacing." in argumentText ||
            staticVisualConstantPattern.containsMatchIn(argumentText)
    }

    private fun KtCallExpression.hasStaticAlphaArgument(): Boolean {
        val alphaArgument =
            valueArguments.firstOrNull { argument ->
                argument.getArgumentName()?.asName?.identifier == "alpha"
            } ?: return false
        val alphaExpression = alphaArgument.getArgumentExpression()?.text?.trim().orEmpty()
        return alphaExpression.isAlphaLiteral() || staticVisualConstantPattern.containsMatchIn(alphaExpression)
    }

    private fun String.isAlphaLiteral(): Boolean =
        matches(Regex("""(?:0(?:\.\d+)?|1(?:\.0+)?)[fF]?"""))
}

private val staticVisualConstantPattern = Regex("""\b[A-Z][A-Z0-9_]*\b""")

internal class NoSourceSuppressionsRule(
    config: Config,
) : LomoBaseRule(config, "Production source must not use @Suppress to bypass static checks.") {
    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        val file = annotationEntry.containingKtFile
        if (!file.isProductionSource()) return

        val shortName = annotationEntry.shortName?.asString()?.let(file::importedName)?.substringAfterLast('.')
        if (shortName in setOf("Suppress", "SuppressWarnings", "SuppressLint")) {
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
) : LomoBaseRule(config, "Production source must not commit placeholder implementations like TODO() or NotImplementedError().") {
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
) : LomoBaseRule(config, "Production source must not commit branches whose conditions are already constant.") {
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
) : LomoBaseRule(config, "Production source must not keep statements after an unconditional control-transfer expression.") {
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
) : LomoBaseRule(config, "Production source must not keep an else branch when a Boolean when is already exhaustive.") {
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
) : LomoBaseRule(config, "Production source must not duplicate the same top-level helper signature across files in one module.") {
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
) : LomoBaseRule(config, "Production source must not keep non-public top-level declarations with no reachable in-module references.") {
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

private class NoSwallowedResultRule(
    config: Config,
) : LomoBaseRule(
    config,
    "kotlin.Result from runCatching must not be silently discarded by getOrNull / getOrDefault(<zero/empty>) / getOrElse { <zero/empty> } without onFailure/recover.",
) {
    private val silentTerminals = setOf("getOrNull", "getOrDefault", "getOrElse")
    private val observabilityCalls = setOf("onFailure", "recover", "recoverCatching")
    private val optOutMarker = Regex("""behavior-contract:\s*silent-result-ok""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName !in silentTerminals) return

        val qualified = expression.parent as? KtDotQualifiedExpression ?: return
        if (qualified.selectorExpression !== expression) return

        val chainNames = collectChainCalleeNames(qualified.receiverExpression)
        if (!chainNames.contains("runCatching")) return
        if (chainNames.any { it in observabilityCalls }) return

        if (!isSilentTerminal(expression, calleeName)) return

        if (qualified.hasOptOutComment(optOutMarker)) return

        reportElement(
            qualified,
            "Swallowed Result: runCatching{} ends in $calleeName without onFailure/recover. " +
                "Handle the failure explicitly, or add `// behavior-contract: silent-result-ok: <reason>` " +
                "on the line above if the silent fallback is intentional.",
        )
    }

    private fun collectChainCalleeNames(start: KtExpression?): List<String> {
        val names = mutableListOf<String>()
        var current: KtExpression? = start
        while (current != null) {
            current =
                when (val node = current) {
                    is KtDotQualifiedExpression -> {
                        (node.selectorExpression as? KtCallExpression)
                            ?.calleeExpression
                            ?.text
                            ?.let(names::add)
                        node.receiverExpression
                    }
                    is KtCallExpression -> {
                        node.calleeExpression?.text?.let(names::add)
                        null
                    }
                    else -> null
                }
        }
        return names
    }

    private fun isSilentTerminal(call: KtCallExpression, name: String): Boolean =
        when (name) {
            "getOrNull" -> true
            "getOrDefault" ->
                isLiteralOrEmptyExpression(call.valueArguments.firstOrNull()?.getArgumentExpression())
            "getOrElse" -> isLambdaBodyConstant(call.lambdaArguments.firstOrNull())
            else -> false
        }

    private fun isLambdaBodyConstant(lambdaArg: KtLambdaArgument?): Boolean {
        val lambda = lambdaArg?.getLambdaExpression() ?: return false
        val body = lambda.functionLiteral.bodyBlockExpression ?: return false
        val singleStatement = body.statements.singleOrNull() ?: return false
        return isLiteralOrEmptyExpression(singleStatement)
    }

    private fun isLiteralOrEmptyExpression(expr: KtExpression?): Boolean {
        if (expr == null) return false
        if (expr is KtConstantExpression) {
            val text = expr.text.trim()
            return text == "null" ||
                text == "0" || text == "0L" || text == "0f" || text == "0F" ||
                text == "0.0" || text == "0.0f" || text == "0.0F" ||
                text == "false"
        }
        if (expr is KtStringTemplateExpression) {
            return expr.entries.isEmpty()
        }
        return isEmptyCollectionCall(expr)
    }

    private fun isEmptyCollectionCall(expr: KtExpression): Boolean {
        val call = expr as? KtCallExpression ?: return false
        val name = call.calleeExpression?.text ?: return false
        val isEmptyFactory = name in setOf("emptyList", "emptyMap", "emptySet")
        val isLiteralFactory = name in setOf("listOf", "mapOf", "setOf") && call.valueArguments.isEmpty()
        return isEmptyFactory || isLiteralFactory
    }
}

private class NoDeprecatedKeptRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Production source must not use @Deprecated. Migrate every caller and delete the old API in the same change; @Deprecated as a soft-migration landing pad is forbidden.",
) {
    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        val file = annotationEntry.containingKtFile
        if (!file.isProductionSource()) return

        if (annotationEntry.shortName?.asString() == "Deprecated") {
            report(
                Finding(
                    Entity.from(annotationEntry),
                    "Do not use @Deprecated in production source. Migrate every caller and delete the old API in the same change; @Deprecated as a soft-migration landing pad is forbidden.",
                ),
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
