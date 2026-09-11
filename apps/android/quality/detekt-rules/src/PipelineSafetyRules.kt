package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtValueArgument

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
        body.statements.takeWhile { it.textRange.startOffset < conversion.textRange.startOffset }.any { statement ->
            if (statement.rethrows(parameter)) return@any true
            val guard = statement as? KtIfExpression ?: return@any false
            val condition = guard.condition as? KtIsExpression ?: return@any false
            !condition.isNegated &&
                condition.leftHandSide.text == parameter &&
                condition.typeReference?.text?.substringAfterLast('.') == "CancellationException" &&
                guard.then?.rethrows(parameter) == true
        }
}

private fun KtExpression.rethrows(parameter: String): Boolean =
    when (this) {
        is KtThrowExpression -> thrownExpression?.text == parameter
        is KtBlockExpression -> statements.lastOrNull()?.rethrows(parameter) == true
        else -> false
    }

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
