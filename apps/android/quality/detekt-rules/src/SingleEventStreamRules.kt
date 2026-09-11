package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal class NoEventInStateFlowRule(
    config: Config,
) : LomoBaseRule(
    config,
    "One-shot UI events may only flow through the acknowledged queue pattern (PendingUiEvent<T> + consume by id " +
        "via an event-queue coordinator); parking Event/Effect/Request payloads in a ViewModel StateFlow replays " +
        "them on recomposition. See docs/udf-contract.md.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*state-event-ok""")
    private val eventPayloadPattern =
        Regex("""[A-Za-z_][A-Za-z0-9_]*(?:Event|Effect|Request)\??(?=[>,)\s]|$)""")
    private val stateFlowGenericPattern = Regex("""(?:Mutable)?StateFlow<([^<>()]*)""")

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (property.isLocal) return

        val containingClass = property.containingClassOrObject ?: return
        if (!containingClass.isViewModelLike()) return
        if (property.hasOptOutComment(optOutMarker)) return

        val typeText = property.typeReference?.text.orEmpty()
        val initText = property.initializer?.text.orEmpty()
        if (!(typeText + initText).contains("StateFlow")) return

        if (containingClass.isAcknowledgedQueueShape(typeText, initText, property)) return

        val payloadTexts = listOf(typeText) + stateFlowGenericPattern.findAll(initText).map { it.groupValues[1] }
        if (payloadTexts.none { text -> eventPayloadPattern.containsMatchIn(text) }) return

        reportElement(
            property,
            "One-shot event surface '${property.name}' ($typeText) in ${containingClass.name}: StateFlow retains its " +
                "last value, so Event/Effect/Request payloads replay on recomposition. Route one-shot UI events through " +
                "the acknowledged queue (PendingUiEvent<T> + consume by id via an event-queue coordinator). " +
                "See docs/udf-contract.md, or mark with '// behavior-contract: state-event-ok: <reason>'.",
        )
    }

    /**
     * Queue-shaped surfaces are legal: the type is/contains StateFlow<List<PendingUiEvent<...>>>, the declaration
     * directly references an event-queue coordinator type, or it delegates to a class member declared with an
     * event-queue coordinator type.
     */
    private fun KtClassOrObject.isAcknowledgedQueueShape(
        typeText: String,
        initText: String,
        property: KtProperty,
    ): Boolean {
        val declarationText = (typeText + initText).replace(" ", "")
        if (declarationText.contains("StateFlow<List<PendingUiEvent")) return true
        if (declarationText.contains("EventQueueCoordinator")) return true
        return delegatesToEventQueueCoordinatorMember(initText, property)
    }

    private fun KtClassOrObject.delegatesToEventQueueCoordinatorMember(
        initText: String,
        self: KtProperty,
    ): Boolean {
        val members =
            (this as? KtClass)?.let { klass ->
                klass.body?.declarations
                    ?.filterIsInstance<KtProperty>()
                    ?.mapNotNull { member -> member.name?.let { name -> name to member.text } }
                    .orEmpty() +
                    klass.primaryConstructorParameters
                        .mapNotNull { parameter ->
                            parameter.name?.let { name -> name to parameter.typeReference?.text.orEmpty() }
                        }
            }.orEmpty()
        return members.any { (name, declarationText) ->
            name != self.name && initText.contains(name) && declarationText.contains("EventQueueCoordinator")
        }
    }
}

internal class NoMultipleEffectChannelsRule(
    config: Config,
) : LomoBaseRule(
    config,
    "The acknowledged event queue (PendingUiEvent<T> + consume by id) is the only legal one-shot UI event surface; " +
        "any parallel Channel or MutableSharedFlow effect surface in a ViewModel is forbidden. See docs/udf-contract.md.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*multiple-channels-ok""")
    private val channelTypePattern = Regex("""(^|[^A-Za-z])Channel<""")
    private val channelCtorPattern = Regex("""(^|[^A-Za-z])Channel\(""")
    private val mutableSharedFlowPattern = Regex("""(^|[^A-Za-z])MutableSharedFlow""")

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (property.isLocal) return

        val containingClass = property.containingClassOrObject ?: return
        if (!containingClass.isViewModelLike()) return
        if (property.hasOptOutComment(optOutMarker)) return

        val typeText = property.typeReference?.text.orEmpty()
        val initText = property.initializer?.text.orEmpty()
        val isEffectSurface =
            channelTypePattern.containsMatchIn(typeText) ||
                channelTypePattern.containsMatchIn(initText) ||
                channelCtorPattern.containsMatchIn(initText) ||
                mutableSharedFlowPattern.containsMatchIn(typeText) ||
                mutableSharedFlowPattern.containsMatchIn(initText)
        if (!isEffectSurface) return

        reportElement(
            property,
            "Forbidden one-shot surface '${property.name}' in ${containingClass.name}: parallel Channel/" +
                "MutableSharedFlow effect channels lose events (no acknowledge, no replay guarantee). The acknowledged " +
                "queue (PendingUiEvent<T> + consume by id via an event-queue coordinator) is the only legal one-shot " +
                "surface. See docs/udf-contract.md, or mark with '// behavior-contract: multiple-channels-ok: <reason>'.",
        )
    }
}

private fun KtClassOrObject.isViewModelLike(): Boolean =
    name?.endsWith("ViewModel") == true ||
        (this as? KtClass)?.superTypeListEntries?.any { it.text.contains("ViewModel") } == true
