package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThrowExpression

internal class NoSilentCatchFallbackRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Catch blocks must not silently discard failures by being empty or returning zero/empty/null defaults without an explicit behavior contract.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*silent-result-ok""")

    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        val file = catchClause.containingKtFile
        if (!file.isProductionSource()) return
        if (catchClause.hasOptOutComment(optOutMarker)) return

        val body = catchClause.catchBody as? KtBlockExpression ?: return
        val statements = body.statements

        if (statements.isEmpty()) {
            reportElement(
                catchClause,
                "Empty catch block: exceptions must not be swallowed silently. Handle, rethrow, or add '// behavior-contract: silent-result-ok: <reason>'.",
            )
            return
        }

        if (statements.any { it is KtThrowExpression }) return

        val lastStatement = statements.lastOrNull() ?: return
        if (isSilentDefault(lastStatement)) {
            val valueText = extractValueText(lastStatement)
            reportElement(
                catchClause,
                "Silent catch fallback: catch block discards failure and returns '$valueText' without surfacing the failure. " +
                    "Model the failure explicitly, rethrow, or document intentional fallback with '// behavior-contract: silent-result-ok: <reason>'.",
            )
        }
    }

    private fun isSilentDefault(statement: KtExpression): Boolean {
        val expr = (statement as? KtReturnExpression)?.returnedExpression ?: statement
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
        if (expr is KtCallExpression) {
            val name = expr.calleeExpression?.text ?: return false
            val isEmptyFactory = name in setOf("emptyList", "emptyMap", "emptySet")
            val isLiteralFactory = name in setOf("listOf", "mapOf", "setOf") && expr.valueArguments.isEmpty()
            return isEmptyFactory || isLiteralFactory
        }
        return false
    }

    private fun extractValueText(statement: KtExpression): String {
        val expr = (statement as? KtReturnExpression)?.returnedExpression ?: statement
        return expr.text.trim()
    }
}

internal class NoSwallowedThrowableRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Catching Throwable directly is forbidden: it swallows JVM fatal errors (e.g. OutOfMemoryError, VirtualMachineError). Catch Exception instead.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*silent-result-ok""")

    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        val file = catchClause.containingKtFile
        if (!file.isProductionSource()) return
        if (catchClause.hasOptOutComment(optOutMarker)) return

        val typeText = catchClause.catchParameter?.typeReference?.text?.trim() ?: return
        if (typeText != "Throwable" && typeText != "java.lang.Throwable") return

        val body = catchClause.catchBody as? KtBlockExpression ?: return
        val paramName = catchClause.catchParameter?.name
        val rethrowsCaught = body.statements.any { stmt ->
            stmt is KtThrowExpression && (paramName == null || stmt.thrownExpression?.text == paramName)
        }
        if (rethrowsCaught) return

        reportElement(
            catchClause,
            "Catching Throwable directly is forbidden: it swallows fatal JVM errors. " +
                "Catch Exception or specific error types instead, rethrow, or document intentional containment with '// behavior-contract: silent-result-ok: <reason>'.",
        )
    }
}
