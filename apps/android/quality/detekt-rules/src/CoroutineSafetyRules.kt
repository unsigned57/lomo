package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

internal class NoSwallowedCancellationInSuspendRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Catching generic Exception, Throwable, or using runCatching inside suspend functions without checking and re-throwing CancellationException breaks structured concurrency. Rethrow CancellationException or mark with `// behavior-contract: cancellation-swallowed-ok: <reason>`.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*(cancellation-swallowed-ok|silent-result-ok)""")

    private val broadExceptionTypes = setOf(
        "Exception", "Throwable", "RuntimeException", "Error"
    )

    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        val file = catchClause.containingKtFile
        if (!file.isProductionSource()) return

        val enclosingFunction = generateSequence(catchClause.parent) { it.parent }
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull() ?: return

        if (!enclosingFunction.hasModifier(KtTokens.SUSPEND_KEYWORD)) return

        val parameter = catchClause.parameterList?.parameters?.firstOrNull()
        val typeText = parameter?.typeReference?.text?.trim()

        val isBroadCatch = typeText == null ||
            broadExceptionTypes.any { it == typeText || typeText.endsWith(".$it") }

        if (!isBroadCatch) return

        val catchBody = catchClause.catchBody ?: return
        val bodyText = catchBody.text

        val hasCancellationRethrow = bodyText.contains("CancellationException") &&
            (bodyText.contains("throw") || bodyText.contains("rethrow"))

        if (!hasCancellationRethrow) {
            if (catchClause.hasOptOutComment(optOutMarker)) return
            report(
                Finding(
                    Entity.from(catchClause),
                    "Swallowed CancellationException in suspend function '${enclosingFunction.name}': " +
                        "catching '$typeText' without rethrowing CancellationException breaks coroutine cancellation. " +
                        "Add 'if (e is CancellationException) throw e' or mark with `// behavior-contract: cancellation-swallowed-ok: <reason>`.",
                ),
            )
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val callee = expression.calleeExpression?.text ?: return
        if (callee != "runCatching") return

        val enclosingFunction = generateSequence(expression.parent) { it.parent }
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull() ?: return

        if (!enclosingFunction.hasModifier(KtTokens.SUSPEND_KEYWORD)) return

        if (expression.hasOptOutComment(optOutMarker)) return

        reportElement(
            expression,
            "Unchecked 'runCatching' in suspend function '${enclosingFunction.name}': " +
                "runCatching catches all Throwables including CancellationException, breaking structured concurrency. " +
                "Use an explicit try-catch with 'if (e is CancellationException) throw e' or mark with `// behavior-contract: silent-result-ok: <reason>`.",
        )
    }
}

internal class NoUnboundedFlowSharingRule(
    config: Config,
) : LomoBaseRule(
    config,
    "UI state flows must use SharingStarted.WhileSubscribed(...) (e.g. appWhileSubscribed()) instead of Lazily or Eagerly to prevent background battery drain, flow retention, and memory leaks.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*(unbounded-flow-ok|lazy-flow-ok|eager-flow-ok)""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        val path = file.path()
        if (!path.contains("/app/src/")) return

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName != "stateIn" && calleeName != "shareIn") return

        for (arg in expression.valueArguments) {
            val text = arg.text
            val isLazily = text.contains("SharingStarted.Lazily") || text == "Lazily"
            val isEagerly = text.contains("SharingStarted.Eagerly") || text == "Eagerly"

            if (isLazily || isEagerly) {
                if (expression.hasOptOutComment(optOutMarker)) return
                val target = arg.getArgumentExpression() ?: expression
                val strategy = if (isLazily) "SharingStarted.Lazily" else "SharingStarted.Eagerly"
                reportElement(
                    target,
                    "Forbidden $strategy in UI layer (/app/src/): UI flows must use " +
                        "SharingStarted.WhileSubscribed(...) (or appWhileSubscribed()) to stop upstream collection when views detach. " +
                        "Or mark with `// behavior-contract: unbounded-flow-ok: <reason>`.",
                )
            }
        }
    }
}

internal class NoUnmanagedCoroutineScopeRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Creating unmanaged CoroutineScope instances or using GlobalScope in production code is forbidden. Inject an application scope or use structured concurrency (coroutineScope / viewModelScope).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*unmanaged-scope-ok""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val callee = expression.calleeExpression?.text ?: return

        if (callee == "CoroutineScope") {
            val function = generateSequence(expression.parent) { it.parent }
                .filterIsInstance<KtNamedFunction>()
                .firstOrNull()
            val isDiProvider = function?.annotationEntries?.any { entry ->
                val name = entry.shortName?.asString()
                name == "Provides" || name == "Singleton" || name == "ApplicationScope"
            } == true
            if (isDiProvider) return

            if (expression.hasOptOutComment(optOutMarker)) return
            reportElement(
                expression,
                "Forbidden unmanaged CoroutineScope(...) in production source: " +
                    "instantiating ad-hoc CoroutineScope causes detached jobs and coroutine leaks. " +
                    "Inject an @ApplicationScope or use structured concurrency (coroutineScope / viewModelScope), " +
                    "or mark with `// behavior-contract: unmanaged-scope-ok: <reason>`.",
            )
        }
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        super.visitSimpleNameExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val isImportOrPackage = generateSequence(expression.parent) { it.parent }
            .any { it is org.jetbrains.kotlin.psi.KtImportDirective || it is org.jetbrains.kotlin.psi.KtPackageDirective }
        if (isImportOrPackage) return

        if (expression.getReferencedName() == "GlobalScope") {
            if (expression.hasOptOutComment(optOutMarker)) return
            reportElement(
                expression,
                "Forbidden GlobalScope usage: GlobalScope bypasses structured concurrency and lifecycle management. " +
                    "Inject an @ApplicationScope instead, or mark with `// behavior-contract: unmanaged-scope-ok: <reason>`.",
            )
        }
    }
}
