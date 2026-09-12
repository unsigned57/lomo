package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType

internal class NoSwallowedCancellationInPagingSourceRule(
    config: Config,
) : LomoBaseRule(
    config,
    "PagingSource load implementations must propagate cancellation before converting a caught failure to LoadResult.Error.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.containingKtFile.isProductionSource()) return
        if (expression.calleeExpression?.text != "Error") return
        val qualified = expression.parent as? KtDotQualifiedExpression ?: return
        if (qualified.receiverExpression.text.substringAfterLast('.') != "LoadResult") return
        val parents = generateSequence(expression.parent) { it.parent }.toList()
        val function = parents.filterIsInstance<KtNamedFunction>().firstOrNull() ?: return
        if (function.name != "load") return
        if (!function.containingKtFile.text.contains("PagingSource")) return

        val boundary = parents.takeWhile { it != function }.firstNotNullOfOrNull { parent ->
            when (parent) {
                is KtCatchClause -> parent.cancellationBoundary()
                is KtLambdaExpression -> parent.failureBoundary()
                else -> null
            }
        } ?: return
        if (boundary.rethrowsBefore(qualified)) return

        reportElement(
            expression,
            "Swallowed CancellationException in PagingSource.load: when catching or folding errors, " +
                "CancellationException must be explicitly re-thrown before converting to LoadResult.Error.",
        )
    }
}

private data class FailureBoundary(val parameter: String, val body: KtBlockExpression) {
    fun rethrowsBefore(conversion: KtExpression): Boolean =
        body.statements.firstOrNull()?.let { first ->
            first.textRange.startOffset < conversion.textRange.startOffset && first.rethrowsCancellation(parameter)
        } == true
}

internal fun KtExpression.rethrowsCancellation(parameter: String): Boolean {
    if (rethrows(parameter)) return true
    val guard = this as? KtIfExpression ?: return false
    return guard.condition?.coversCancellation(parameter) == true &&
        guard.then?.rethrows(parameter) == true
}

internal fun KtBlockExpression.preservesCaughtCancellation(parameter: String): Boolean =
    statements.firstOrNull()?.rethrowsCancellation(parameter) == true || rethrows(parameter)

private fun KtExpression.coversCancellation(parameter: String): Boolean = when (this) {
    is KtParenthesizedExpression -> expression?.coversCancellation(parameter) == true
    is KtIsExpression -> !isNegated && leftHandSide.text == parameter &&
        typeReference?.text?.let { containingKtFile.importedName(it).substringAfterLast('.') } == "CancellationException"
    is KtBinaryExpression -> when (operationToken) {
        KtTokens.OROR -> left?.coversCancellation(parameter) == true || right?.coversCancellation(parameter) == true
        KtTokens.ANDAND -> left?.coversCancellation(parameter) == true && right?.coversCancellation(parameter) == true
        else -> false
    }
    else -> false
}

private fun KtExpression.rethrows(parameter: String): Boolean =
    when (this) {
        is KtThrowExpression -> thrownExpression?.text == parameter
        is KtBlockExpression -> statements.lastOrNull()?.rethrows(parameter) == true &&
            statements.dropLast(1).none { it.canExitBeforeRethrow() }
        else -> false
    }

private fun KtExpression.canExitBeforeRethrow(): Boolean =
    this is KtReturnExpression || this is KtBreakExpression || this is KtContinueExpression ||
        anyDescendantOfType<KtReturnExpression>() || anyDescendantOfType<KtBreakExpression>() ||
        anyDescendantOfType<KtContinueExpression>()

private fun KtCatchClause.cancellationBoundary(): FailureBoundary? {
    val parameter = catchParameter ?: return null
    val type = parameter.typeReference?.text?.substringAfterLast('.') ?: return null
    if (type !in setOf("Throwable", "Exception", "RuntimeException", "CancellationException")) return null
    val body = catchBody as? KtBlockExpression ?: return null
    return FailureBoundary(parameter.name ?: return null, body)
}

private fun KtLambdaExpression.failureBoundary(): FailureBoundary? {
    val argument = parent as? KtValueArgument ?: return null
    val call = generateSequence(argument.parent) { it.parent }
        .filterIsInstance<KtCallExpression>().firstOrNull() ?: return null
    val handlesFailure = when (call.calleeExpression?.text) {
        "fold" -> argument.getArgumentName()?.asName?.identifier == "onFailure" ||
            (argument.getArgumentName() == null && call.valueArguments.indexOf(argument) == 1)
        "getOrElse", "recover", "recoverCatching" -> true
        else -> false
    }
    if (!handlesFailure) return null
    val body = bodyExpression ?: return null
    return FailureBoundary(valueParameters.singleOrNull()?.name ?: "it", body)
}
