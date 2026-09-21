package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/** Complements the early syntax boundary with cross-file, inferred and type-alias resolution. */
internal class NoInferredMutableFlowExposureRule(config: Config) : LomoBaseRule(
    config,
    "Public signatures must not infer a mutable flow capability from another file or type alias.",
), RequiresAnalysisApi {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (!property.containingKtFile.isProductionSource() || property.isLocal || property.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        val leaksWriter = analyze(property) { writerTypes.any { property.symbol.returnType.isSubtypeOf(it) } }
        if (leaksWriter) reportElement(property, "Resolved property type exposes a mutable flow writer; declare a read-only projection.")
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.containingKtFile.isProductionSource() || function.isLocal || function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        val leaksWriter = analyze(function) { writerTypes.any { function.symbol.returnType.isSubtypeOf(it) } }
        if (leaksWriter) reportElement(function, "Resolved return type exposes a mutable flow writer; keep that capability inside its owner.")
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (!parameter.containingKtFile.isProductionSource() || !parameter.hasValOrVar() || parameter.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        val leaksWriter = analyze(parameter) { writerTypes.any { parameter.symbol.returnType.isSubtypeOf(it) } }
        if (leaksWriter) reportElement(parameter, "Resolved constructor property exports a mutable flow writer.")
    }

    private companion object {
        val writerTypes = listOf(
            ClassId.fromString("kotlinx/coroutines/flow/MutableStateFlow"),
            ClassId.fromString("kotlinx/coroutines/flow/MutableSharedFlow"),
        )
    }
}
