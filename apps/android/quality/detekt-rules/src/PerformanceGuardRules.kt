package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtWhileExpression

internal class NoLoopBoundaryIoRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Calling I/O, database, FFI, or repository boundary methods inside loops or collection iterations causes N+1 query amplification. Use batch or bulk APIs instead.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*loop-io-ok""")

    private val loopIterators = setOf(
        "forEach", "forEachIndexed", "map", "mapNotNull", "mapIndexed",
        "flatMap", "filter", "filterNotNull", "filterIndexed"
    )

    private val forbiddenReceivers = Regex("""^(port|bridge|native|dataStore|.*[rR]epository|.*[sS]ervice|.*[eE]ngine|.*[cC]lient)$""")

    private val forbiddenIoMethods = setOf(
        "getMemo", "applyMemoCommand", "commitDocumentMutation", "queryMemos",
        "queryCount", "findMemoSnapshot", "readWorkspaceScanPage", "startWorkspaceScan",
        "startRebuild", "refreshMemos"
    )

    override fun visitForExpression(expression: KtForExpression) {
        super.visitForExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        checkLoopBody(expression.body ?: return)
    }

    override fun visitWhileExpression(expression: KtWhileExpression) {
        super.visitWhileExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        checkLoopBody(expression.body ?: return)
    }

    override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
        super.visitDoWhileExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        checkLoopBody(expression.body ?: return)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val callee = expression.calleeExpression?.text ?: return
        if (callee in loopIterators) {
            val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression()
            if (lambda != null) {
                checkLoopBody(lambda.bodyExpression ?: return)
            }
        }
    }

    private fun checkLoopBody(body: KtExpression) {
        body.accept(object : KtTreeVisitorVoid() {
            override fun visitDotQualifiedExpression(dotExpr: KtDotQualifiedExpression) {
                super.visitDotQualifiedExpression(dotExpr)
                val receiverText = dotExpr.receiverExpression.text.trim()
                if (receiverText.firstOrNull()?.isUpperCase() == true) return

                val selectorCall = dotExpr.selectorExpression as? KtCallExpression ?: return
                val selectorName = selectorCall.calleeExpression?.text ?: return

                val isForbiddenReceiver = forbiddenReceivers.matches(receiverText)
                val isForbiddenMethod = selectorName in forbiddenIoMethods

                if (isForbiddenReceiver || isForbiddenMethod) {
                    if (dotExpr.hasOptOutComment(optOutMarker)) return
                    reportElement(
                        dotExpr,
                        "Loop-boundary I/O anti-pattern (N+1 query): calling '$receiverText.$selectorName' " +
                            "inside a loop or collection iteration causes severe latency and lock contention. " +
                            "Use a batch/bulk API instead, or mark with `// behavior-contract: loop-io-ok: <reason>`.",
                    )
                }
            }
        })
    }
}

internal class NoNestedCollectionScanRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Linear search (contains, any, firstOrNull, find) on a collection inside an outer iteration produces O(N^2) Cartesian complexity. Pre-index into a Set or Map instead.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*nested-scan-ok""")

    private val outerIterators = setOf(
        "filter", "filterNot", "filterIndexed", "map", "mapNotNull", "mapIndexed", "flatMap", "forEach", "forEachIndexed"
    )

    private val linearScanMethods = setOf(
        "any", "firstOrNull", "find", "first", "indexOf", "none"
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val callee = expression.calleeExpression?.text ?: return
        val isLinearScan = callee in linearScanMethods
        val isContains = callee == "contains"
        if (!isLinearScan && !isContains) return

        if (isLinearScan && expression.valueArguments.isEmpty() && expression.lambdaArguments.isEmpty()) {
            return
        }

        val dotParent = expression.parent as? KtDotQualifiedExpression ?: return
        val receiver = dotParent.receiverExpression
        val receiverText = receiver.text.trim()

        val enclosingLambda = generateSequence(expression.parent) { it.parent }
            .filterIsInstance<org.jetbrains.kotlin.psi.KtLambdaExpression>()
            .firstOrNull() ?: return

        val enclosingCall = (enclosingLambda.parent as? org.jetbrains.kotlin.psi.KtLambdaArgument)?.parent as? KtCallExpression
            ?: enclosingLambda.parent as? KtCallExpression ?: return
        val enclosingCallee = enclosingCall.calleeExpression?.text ?: return
        if (enclosingCallee !in outerIterators) return

        val enclosingParamName = enclosingLambda.valueParameters.firstOrNull()?.name ?: "it"
        if (receiverText == enclosingParamName || receiverText.startsWith("$enclosingParamName.")) {
            return
        }

        if (isContains) {
            if (!receiverText.endsWith("List") && !receiverText.endsWith("Items") &&
                !receiverText.endsWith("Memos") && !receiverText.endsWith("Tags") &&
                !receiverText.startsWith("all") && !receiverText.startsWith("existing")
            ) {
                return
            }
        }

        if (expression.hasOptOutComment(optOutMarker)) return

        val message = if (isContains) {
            "Nested collection linear contains anti-pattern (O(N^2) complexity): calling '$receiverText.contains(...)' " +
                "inside collection iteration '$enclosingCallee'. Ensure '$receiverText' is a Set or pre-index it, " +
                "or mark with `// behavior-contract: nested-scan-ok: <reason>`."
        } else {
            "Nested collection scan anti-pattern (O(N^2) complexity): calling '$receiverText.$callee { ... }' " +
                "inside collection iteration '$enclosingCallee'. Pre-index '$receiverText' into a Set (toSet()) or Map (associateBy()) before iterating, " +
                "or mark with `// behavior-contract: nested-scan-ok: <reason>`."
        }
        reportElement(expression, message)
    }
}

internal class NoFullRebuildInLocalMutationRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Local mutation functions (update, toggle, delete, markDone, snooze) must not trigger full database rebuilds (startRebuild, refreshMemos). Local changes must update projections incrementally.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*full-rebuild-ok""")

    private val localMutationPrefixes = listOf(
        "update", "toggle", "delete", "mark", "snooze", "pin", "unpin", "restore", "create"
    )

    private val globalRebuildCallees = setOf(
        "startRebuild", "refreshMemos", "publishRebuild", "rebuildFromCurrentWorkspace"
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return

        val functionName = function.name ?: return
        val lowerName = functionName.lowercase()

        // Check if function name matches local mutation patterns
        val isLocalMutation = localMutationPrefixes.any { prefix -> lowerName.startsWith(prefix) }
        // Exclude methods that are legitimately full-rebuild entry points
        val isFullRebuildOwner = lowerName.contains("rebuild") || lowerName.contains("refreshall") ||
            lowerName.contains("import") || lowerName.contains("sync") || lowerName.contains("init")
        if (!isLocalMutation || isFullRebuildOwner) return

        function.bodyExpression?.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(call: KtCallExpression) {
                super.visitCallExpression(call)
                val callee = call.calleeExpression?.text ?: return
                if (callee in globalRebuildCallees) {
                    if (call.hasOptOutComment(optOutMarker)) return
                    reportElement(
                        call,
                        "Compensating full-rebuild anti-pattern: calling '$callee()' inside local mutation function '$functionName'. " +
                            "Local mutations must update only their target projection, not rebuild the entire database. " +
                            "If intentional, mark with `// behavior-contract: full-rebuild-ok: <reason>`.",
                    )
                }
            }
        })
    }
}
