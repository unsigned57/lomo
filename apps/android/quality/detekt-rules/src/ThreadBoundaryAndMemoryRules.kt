package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.parents

internal class NoUnconfinedIoOrNativeRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Blocking I/O and native calls must not execute outside Dispatchers.IO to prevent UI frame drops and ANRs.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*(blocking-io-ok|io-context-ok)""")
    private val ioCalleeNames = setOf(
        "readText",
        "writeText",
        "decodeFile",
        "decodeStream",
        "createNewFile",
        "createTempFile",
        "deleteRecursively",
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName !in ioCalleeNames) return

        if (expression.hasOptOutComment(optOutMarker)) return

        val isInsideIoContext = expression.parents.any { parent ->
            if (parent is KtCallExpression) {
                val name = parent.calleeExpression?.text
                if (name == "withContext") {
                    val argsText = parent.valueArgumentList?.text.orEmpty()
                    argsText.contains("IO") || argsText.contains("io") || argsText.contains("dispatcher")
                } else false
            } else false
        }

        if (isInsideIoContext) return

        reportElement(
            expression,
            "Blocking I/O operation '$calleeName' executed outside Dispatchers.IO. " +
                "Direct disk or stream I/O blocks the caller thread (risking UI frame drops or ANRs). " +
                "Wrap in 'withContext(Dispatchers.IO)' or document with '// behavior-contract: blocking-io-ok: <reason>'.",
        )
    }
}

internal class NoUnpaginatedFullLoadRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Unpaginated full-dataset queries and unbuffered stream reads are forbidden to prevent memory ballooning and OOM.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*(full-load-ok|unpaginated-ok)""")
    private val fullScanPrefixes = listOf("getAll", "loadAll", "readAll", "fetchAll")
    private val paginationParams = setOf("limit", "pagesize", "page", "cursor", "offset", "count")

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return

        val name = function.name ?: return
        if (fullScanPrefixes.none { name.startsWith(it) }) return

        val returnType = function.typeReference?.text.orEmpty()
        val returnsCollection = returnType.startsWith("List<") ||
            returnType.startsWith("Set<") ||
            returnType.startsWith("Collection<") ||
            returnType.startsWith("Map<")
        if (!returnsCollection) return

        val paramNames = function.valueParameters.mapNotNull { it.name?.lowercase() }
        val hasPagination = paramNames.any { param -> paginationParams.any { param.contains(it) } }
        if (hasPagination) return

        if (function.hasOptOutComment(optOutMarker)) return

        reportElement(
            function,
            "Unpaginated full-dataset query '$name' returning '$returnType' forbidden in production architecture. " +
                "Loading unbounded collections into memory causes memory ballooning and OOM. " +
                "Add a limit/pageSize parameter, return a streaming Flow/PagingData, or document with '// behavior-contract: unpaginated-ok: <reason>'.",
        )
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName != "readBytes") return

        if (expression.hasOptOutComment(optOutMarker)) return

        reportElement(
            expression,
            "Unbounded memory allocation: calling 'readBytes()' loads the entire file/stream into heap memory. " +
                "Use streaming, buffer chunks, or document bounded size with '// behavior-contract: full-load-ok: <reason>'.",
        )
    }
}
