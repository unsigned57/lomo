package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal class NoMutableFlowExposureRule(config: Config) : LomoBaseRule(
    config,
    "MutableStateFlow and MutableSharedFlow belong to one writer; expose read-only streams outside the owner.",
) {
    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (!property.containingKtFile.isProductionSource() || property.isLocal || property.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        val type = property.typeReference
        val exposesWriter = type?.containsMutableFlow() == true ||
            (type == null && (property.initializer ?: property.getter?.bodyExpression)?.isMutableFlow(property, emptySet()) == true)
        if (exposesWriter) reportElement(property, "Mutable flow '${property.name}' escapes its owner; keep the writer private and expose StateFlow/SharedFlow.")
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        if (!parameter.containingKtFile.isProductionSource() || !parameter.hasValOrVar() || parameter.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        if (parameter.typeReference?.containsMutableFlow() == true) {
            reportElement(parameter, "Constructor property '${parameter.name}' exports a mutable flow writer; make it private.")
        }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.containingKtFile.isProductionSource() || function.isLocal || function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        if (function.typeReference?.containsMutableFlow() == true) {
            reportElement(function, "Function '${function.name}' exports a mutable flow writer; return a read-only stream.")
        }
    }

    private fun KtTypeReference.containsMutableFlow(seenAliases: Set<String> = emptySet()): Boolean =
        collectDescendantsOfType<KtUserType>().any { type ->
            val name = type.referencedName
            name != null && (containingKtFile.importedName(name).substringAfterLast('.') in mutableFlowNames ||
                (name !in seenAliases && containingKtFile.declarations.filterIsInstance<KtTypeAlias>()
                    .firstOrNull { it.name == name }?.getTypeReference()?.containsMutableFlow(seenAliases + name) == true))
        }

    private fun KtExpression.isMutableFlow(owner: KtProperty, seen: Set<String>): Boolean = when (this) {
        is KtParenthesizedExpression -> expression?.isMutableFlow(owner, seen) == true
        is KtCallExpression -> canonicalCalleeName() in mutableFlowNames ||
            (canonicalCalleeName() == "lazy" && lambdaArguments.singleOrNull()?.getLambdaExpression()
                ?.bodyExpression?.isMutableFlow(owner, seen) == true)
        is KtBlockExpression -> statements.lastOrNull()?.isMutableFlow(owner, seen) == true
        is KtReturnExpression -> returnedExpression?.isMutableFlow(owner, seen) == true
        is KtNameReferenceExpression -> referencedWriter(owner, getReferencedName(), seen)
        else -> false
    }

    private fun referencedWriter(owner: KtProperty, name: String, seen: Set<String>): Boolean {
        if (name in seen) return false // Recursive inferred types are rejected by the Kotlin compiler.
        val klass = owner.containingClassOrObject
        val parameter = (klass as? KtClass)?.primaryConstructorParameters?.firstOrNull { it.name == name }
        if (parameter?.typeReference?.containsMutableFlow() == true) return true
        val property = klass?.declarations?.filterIsInstance<KtProperty>()?.firstOrNull { it.name == name }
            ?: owner.containingKtFile.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name }
            ?: return false
        return property.typeReference?.containsMutableFlow() == true ||
            (property.typeReference == null && property.initializer?.isMutableFlow(owner, seen + name) == true)
    }

    private companion object {
        val mutableFlowNames = setOf("MutableStateFlow", "MutableSharedFlow")
    }
}
