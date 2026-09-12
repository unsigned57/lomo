package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.parents

internal class PagingDataCachedInRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Flow<PagingData<*>> surfaces in app source must terminate in cachedIn(scope) before exposure; an uncached " +
        "pager restarts and re-allocates for every collector (Audit F10 audit-01 / D6 audit-07). See docs/udf-contract.md.",
) {
    private val uncachedPagingMarker = Regex("""behavior-contract:\s*uncached-paging-ok""")

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/app/src/")) return
        if (property.isLocal) return

        val typeText = property.typeReference?.text ?: return
        if (!typeText.contains("PagingData") || !typeText.contains("Flow<")) return
        val initializer = property.initializer ?: return
        if (initializer.isCachedPagingValue()) return
        if (property.hasModifier(KtTokens.PRIVATE_KEYWORD) && property.hasOptOutComment(uncachedPagingMarker)) return

        reportElement(
            property,
            "Uncached Flow<PagingData<*>> '${property.name}' in ${property.containingClassOrObject?.name ?: file.name}: " +
                "the paging chain must terminate in cachedIn(scope) before exposure, otherwise every collector restarts " +
                "the pager (Audit F10 audit-01 / D6 audit-07). Private intermediates feeding a cachedIn-terminated " +
                "public surface may register with '// behavior-contract: uncached-paging-ok: <reason>'. " +
                "See docs/udf-contract.md.",
        )
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/app/src/")) return
        if (function.isLocal) return

        val returnTypeText = function.typeReference?.text ?: return
        if (!returnTypeText.contains("PagingData") || !returnTypeText.contains("Flow<")) return
        val body = function.bodyExpression ?: return
        val results = if (body is KtBlockExpression) {
            body.collectDescendantsOfType<KtReturnExpression>()
                .filter { it.getTargetLabel() == null && it.parents.filterIsInstance<KtNamedFunction>().firstOrNull() == function }
                .mapNotNull { it.returnedExpression }
        } else listOf(body)
        if (results.isNotEmpty() && results.all { it.isCachedPagingValue() }) return

        reportElement(
            function,
            "Uncached Flow<PagingData<*>> return '${function.name}' in ${file.name}: the paging chain must terminate " +
                "in cachedIn(scope) before exposure, otherwise every collector restarts the pager " +
                "(Audit F10 audit-01 / D6 audit-07). See docs/udf-contract.md.",
        )
    }

    private fun KtExpression.isCachedPagingValue(): Boolean = when (this) {
        is KtParenthesizedExpression -> expression?.isCachedPagingValue() == true
        is KtIfExpression -> then?.isCachedPagingValue() == true && `else`?.isCachedPagingValue() == true
        is KtWhenExpression -> entries.isNotEmpty() && entries.all { it.expression?.isCachedPagingValue() == true }
        is KtBlockExpression -> statements.lastOrNull()?.isCachedPagingValue() == true
        is KtDotQualifiedExpression -> hasCachedPagingReceiver() || isPurePassThrough()
        is KtNameReferenceExpression -> true
        else -> false
    }

    private fun KtDotQualifiedExpression.hasCachedPagingReceiver(): Boolean {
        val call = selectorExpression as? KtCallExpression ?: return false
        if (call.canonicalCalleeName() == "cachedIn") return true
        return call.canonicalCalleeName() in setOf("stateIn", "shareIn") &&
            (receiverExpression as? KtDotQualifiedExpression)?.hasCachedPagingReceiver() == true
    }

    private fun KtExpression.isPurePassThrough(): Boolean = when (this) {
        is KtNameReferenceExpression -> true
        is KtDotQualifiedExpression -> receiverExpression.isPurePassThrough() && selectorExpression is KtNameReferenceExpression
        else -> false
    }
}

internal class NoWriteOnlyStateFlowRule(
    config: Config,
) : LomoBaseRule(
    config,
    "A private MutableStateFlow whose class-body occurrences are only writes (.value assignment / update receiver) " +
        "and never read is a leaking accumulator (Audit RF4 audit-04); read it through the screen-state machine or " +
        "delete it. See docs/udf-contract.md.",
) {
    private val writeOnlyMarker = Regex("""behavior-contract:\s*write-only-flow-ok""")
    private val mutableStateFlowPattern = Regex("""(^|[^A-Za-z])MutableStateFlow""")

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/app/src/")) return
        if (property.isLocal) return
        if (!property.hasModifier(KtTokens.PRIVATE_KEYWORD)) return

        val containingClass = property.containingClassOrObject ?: return
        val typeText = property.typeReference?.text.orEmpty()
        val initText = property.initializer?.text.orEmpty()
        if (!mutableStateFlowPattern.containsMatchIn(typeText + initText)) return
        if (property.hasOptOutComment(writeOnlyMarker)) return

        val flowName = property.name ?: return
        var writeCount = 0
        var readCount = 0
        containingClass.nameOccurrences(flowName, excluding = property).forEach { occurrence ->
            if (occurrence.isWrite) writeCount++ else readCount++
        }

        if (writeCount > 0 && readCount == 0) {
            reportElement(
                property,
                "Write-only StateFlow '$flowName' in ${containingClass.name}: the flow is written (.value = / update) " +
                    "but never read in its class (Audit RF4 audit-04) — an accumulating leak nobody consumes. Read it " +
                    "through the screen-state machine or delete it; register cross-file readers with " +
                    "'// behavior-contract: write-only-flow-ok: <reason>'. See docs/udf-contract.md.",
            )
        }
    }

    private fun org.jetbrains.kotlin.psi.KtClassOrObject.nameOccurrences(
        name: String,
        excluding: KtProperty,
    ): List<FlowOccurrence> {
        return collectDescendantsOfType<KtNameReferenceExpression>()
            .filter { it.getReferencedName() == name && !excluding.textRange.contains(it.textRange) }
            .map { FlowOccurrence(isWrite = it.isStateWrite()) }
    }

    private data class FlowOccurrence(val isWrite: Boolean)

    private fun KtNameReferenceExpression.isStateWrite(): Boolean {
        val qualified = parent as? KtDotQualifiedExpression ?: return false
        if (qualified.receiverExpression != this) return false
        val selector = qualified.selectorExpression
        if ((selector as? KtCallExpression)?.canonicalCalleeName() == "update") return true
        if ((selector as? KtNameReferenceExpression)?.getReferencedName() != "value") return false
        val assignment = qualified.parent as? KtBinaryExpression ?: return false
        return assignment.left == qualified && assignment.operationToken in KtTokens.ALL_ASSIGNMENTS
    }
}

internal class NoCollaboratorDefaultArgRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Constructor value parameters must not default-instantiate collaborator types (Bus/Registry/Coordinator by " +
        "configuration); a default value mints an orphan collaborator outside the composition root " +
        "(Audit Q6 audit-03). See docs/udf-contract.md.",
) {
    private val collaboratorDefaultMarker = Regex("""behavior-contract:\s*collaborator-default-ok""")
    private val forbiddenTypeTokens = config.valueOrDefault("forbiddenTypeTokens", emptyList<String>())

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val file = parameter.containingKtFile
        if (!file.isProductionSource()) return
        if (forbiddenTypeTokens.isEmpty()) return

        val constructor = parameter.parent.parent
        if (constructor !is KtPrimaryConstructor && constructor !is KtSecondaryConstructor) return
        if (!parameter.hasDefaultValue()) return

        val typeText = parameter.typeReference?.text ?: return
        val matchedToken = forbiddenTypeTokens.firstOrNull { typeText.contains(it) } ?: return
        if (parameter.hasOptOutComment(collaboratorDefaultMarker)) return

        reportElement(
            parameter,
            "Forbidden default collaborator argument '${parameter.name}' with type $typeText in " +
                "${(constructor as? KtPrimaryConstructor)?.getContainingClassOrObject()?.name ?: file.name}: the " +
                "default value mints an orphan collaborator (token '$matchedToken') outside the composition root " +
                "(Audit Q6 audit-03). Inject the collaborator explicitly at the composition root, or mark with " +
                "'// behavior-contract: collaborator-default-ok: <reason>'. See docs/udf-contract.md.",
        )
    }
}
