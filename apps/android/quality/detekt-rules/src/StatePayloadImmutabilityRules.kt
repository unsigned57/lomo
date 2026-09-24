package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

private val mutableMemberTokens =
    Regex(
        """mutableStateOf|mutableIntStateOf|mutableLongStateOf|mutableFloatStateOf|""" +
            """mutableDoubleStateOf|mutableListOf|mutableMapOf|mutableSetOf|mutableStateListOf|""" +
            """mutableStateMapOf|\bMutableState\b|\bMutableList\b|\bMutableMap\b|\bMutableSet\b|""" +
            """\bMutableCollection\b|\bArrayList\b|\bHashMap\b|\bHashSet\b|\bSnapshotState""",
    )

private fun KtFile.findInFileClass(name: String): KtClass? =
    collectDescendantsOfType<KtClass>().firstOrNull { it.name == name }

/** Flow payload references: `StateFlow<X>`, `Flow<List<X>>`, `MutableStateFlow(X(...))`. */
private fun KtFile.referencesFlowPayload(name: String): Boolean =
    Regex("""\b\w*Flow\s*(?:<[^>]*|\()\s*\b${Regex.escape(name)}\b""").containsMatchIn(text)

private fun memberMutableReason(isVar: Boolean, surfaceText: String): String? = when {
    isVar -> "is a 'var' written outside a copy-based transition"
    mutableMemberTokens.containsMatchIn(surfaceText) -> "has a mutable container or Compose mutable state type"
    else -> null
}

/**
 * Mutable members of a payload type: var members, mutable-container or mutableStateOf members,
 * nested payload declarations (sealed branches, nested states), and member types that resolve to
 * another mutable class in the same file. A read-only view over a mutable payload is not immutable.
 */
private fun KtClass.mutableMemberProblems(seen: Set<String> = emptySet()): List<Pair<KtNamedDeclaration, String>> {
    if (name in seen) return emptyList()
    val nextSeen = seen + listOfNotNull(name)
    val problems = mutableListOf<Pair<KtNamedDeclaration, String>>()
    val memberTypes = mutableListOf<org.jetbrains.kotlin.psi.KtTypeReference>()
    primaryConstructorParameters.forEach { parameter ->
        memberMutableReason(parameter.isMutable, parameter.typeReference?.text.orEmpty())
            ?.let { reason -> problems += parameter to reason }
        parameter.typeReference?.let(memberTypes::add)
    }
    val members = body?.declarations.orEmpty()
    members.filterIsInstance<KtProperty>().forEach { property ->
        val surface = listOfNotNull(
            property.typeReference?.text,
            property.initializer?.text,
            property.delegateExpression?.text,
        ).joinToString(" ")
        memberMutableReason(property.isVar, surface)
            ?.let { reason -> problems += property to reason }
        property.typeReference?.let(memberTypes::add)
    }
    members.filterIsInstance<KtClass>().forEach { nested ->
        problems += nested.mutableMemberProblems(nextSeen)
    }
    val file = containingKtFile
    memberTypes
        .flatMap { it.collectDescendantsOfType<KtUserType>().mapNotNull { t -> t.referencedName } }
        .map { it.substringAfterLast('.') }
        .distinct()
        .mapNotNull { file.findInFileClass(it) }
        .filter { it !== this }
        .forEach { problems += it.mutableMemberProblems(nextSeen) }
    return problems
}

private fun KtClassOrObject.isViewModelLike(): Boolean =
    name?.endsWith("ViewModel") == true ||
        (this as? KtClass)?.superTypeListEntries?.any { it.text.contains("ViewModel") } == true

internal class NoMutableStatePayloadRule(
    config: Config,
) : LomoBaseRule(
    config,
    "State payloads and holder types reaching the UI must be immutable snapshots: a data or sealed " +
        "*State class, a *State class used as a flow payload, and any type a screen ViewModel holds " +
        "cannot carry var members, mutable containers or Compose mutable state. " +
        "See quality/udf-contract.md.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*mutable-payload-ok""")

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        val file = klass.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/")) return
        val name = klass.name ?: return
        if (!name.endsWith("State")) return
        if (klass.hasOptOutComment(optOutMarker)) return
        // A plain *State class is only a payload when a flow references it; otherwise it is a
        // UI-local remembered holder, which the contract assigns to the screen session.
        if (!klass.isData() && !klass.isSealed() && !file.referencesFlowPayload(name)) return
        klass.mutableMemberProblems().forEach { (member, reason) ->
            reportElement(
                member,
                "Mutable member '${member.name}' in payload $name $reason; a state payload reaching " +
                    "the UI must be an immutable snapshot — readers must not observe or perform " +
                    "writes that bypass the owning state machine. See quality/udf-contract.md, or " +
                    "mark with '// behavior-contract: mutable-payload-ok: <reason>'.",
            )
        }
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/") || property.isLocal) return
        val klass = property.containingClassOrObject as? KtClass ?: return
        if (!klass.isViewModelLike()) return
        if (property.hasOptOutComment(optOutMarker)) return
        val surface = listOfNotNull(
            property.typeReference?.text,
            property.initializer?.text,
            property.delegateExpression?.text,
        ).joinToString(" ")
        if (mutableMemberTokens.containsMatchIn(surface)) {
            reportElement(
                property,
                "Mutable container surface '${property.name}' in ${klass.name}: business state must " +
                    "live inside the screen-state machine; a mutable container or Compose state " +
                    "member is a second write channel. See quality/udf-contract.md, or mark with " +
                    "'// behavior-contract: mutable-payload-ok: <reason>'.",
            )
            return
        }
        if (property.referencedClassNames().any { name ->
                file.findInFileClass(name)
                    ?.takeIf { it !== klass }
                    ?.mutableMemberProblems(setOfNotNull(klass.name))
                    ?.isNotEmpty() == true
            }
        ) {
            reportElement(
                property,
                "Mutable holder surface '${property.name}' in ${klass.name}: its type carries " +
                    "mutable members, so UI readers can write behind the state machine. Keep the " +
                    "holder UI-local or make it an immutable snapshot. See quality/udf-contract.md, " +
                    "or mark with '// behavior-contract: mutable-payload-ok: <reason>'.",
            )
        }
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val file = parameter.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/") || !parameter.hasValOrVar()) return
        val klass = parameter.containingClassOrObject as? KtClass ?: return
        if (!klass.isViewModelLike()) return
        if (parameter.hasOptOutComment(optOutMarker)) return
        val names = parameter.typeReference
            ?.collectDescendantsOfType<KtUserType>()
            ?.mapNotNull { it.referencedName?.substringAfterLast('.') }
            .orEmpty()
        if (names.any { name ->
                file.findInFileClass(name)
                    ?.takeIf { it !== klass }
                    ?.mutableMemberProblems(setOfNotNull(klass.name))
                    ?.isNotEmpty() == true
            }
        ) {
            reportElement(
                parameter,
                "Mutable holder dependency '${parameter.name}' in ${klass.name}: its type carries " +
                    "mutable members, so the screen state is not single-sourced. See " +
                    "quality/udf-contract.md, or mark with " +
                    "'// behavior-contract: mutable-payload-ok: <reason>'.",
            )
        }
    }

    private fun KtProperty.referencedClassNames(): Set<String> {
        val names = mutableSetOf<String>()
        typeReference
            ?.collectDescendantsOfType<KtUserType>()
            ?.mapNotNullTo(names) { it.referencedName?.substringAfterLast('.') }
        (initializer as? KtCallExpression)
            ?.calleeExpression
            ?.text
            ?.substringAfterLast('.')
            ?.let(names::add)
        return names
    }
}

internal class NoWriteInFlowDerivationRule(
    config: Config,
) : LomoBaseRule(
    config,
    "A flow-producing derivation must be a pure projection: a write to a Mutable*Flow/Channel " +
        "backing inside an operator lambda whose chain is not consumed by a sink (collect/launchIn/" +
        "first/...) turns downstream observation into a hidden state mutation. See " +
        "quality/udf-contract.md.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*derivation-write-ok""")
    private val backingToken = Regex("""MutableStateFlow|MutableSharedFlow|\bChannel\s*\(""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/")) return
        if (expression.canonicalCalleeName() !in flowOperators) return
        // A sink-terminated chain (collect/launchIn/first/...) is the imperative event path:
        // upstream emissions legitimately transition the owner's state. Only a chain that still
        // produces a flow hides the write behind downstream observation.
        if (outermostChainCallee(expression) in sinkTerminals) return
        if (expression.hasOptOutComment(optOutMarker)) return
        val backing = backingMemberNames(expression)
        if (backing.isEmpty()) return
        for (lambda in expression.lambdaArguments.mapNotNull { it.getLambdaExpression() }) {
            lambda.bodyExpression
                ?.collectDescendantsOfType<KtElement> {
                    it.nearestLambda() == lambda && it.isBackingWrite(backing)
                }
                ?.forEach { write ->
                    reportElement(
                        write,
                        "State write inside a flow-producing derivation in " +
                            "${expression.getStrictParentOfType<KtClassOrObject>()?.name ?: file.name}: observing " +
                            "the produced flow triggers a hidden mutation, so the read path is not " +
                            "pure. Move the transition onto the action path or consume the chain " +
                            "with a sink (collect/launchIn). See quality/udf-contract.md, or mark " +
                            "with '// behavior-contract: derivation-write-ok: <reason>'.",
                    )
                }
        }
    }

    private fun outermostChainCallee(call: KtCallExpression): String? {
        var current: KtExpression = call
        var callee = call.canonicalCalleeName()
        while (true) {
            val parent = current.parent
            if (parent is KtQualifiedExpression && parent.selectorExpression is KtCallExpression) {
                val outer = parent.selectorExpression as KtCallExpression
                callee = outer.canonicalCalleeName()
                current = parent
            } else {
                return callee
            }
        }
    }

    private fun backingMemberNames(context: KtElement): Set<String> {
        val klass = context.getStrictParentOfType<KtClassOrObject>()
        val properties = klass?.body?.declarations?.filterIsInstance<KtProperty>()
            ?: context.containingKtFile.declarations.filterIsInstance<KtProperty>()
        val fromProperties = properties.filter {
            backingToken.containsMatchIn(
                (it.typeReference?.text.orEmpty()) + " " + (it.initializer?.text.orEmpty()),
            )
        }.mapNotNull { it.name }
        val fromCtor = (klass as? KtClass)?.primaryConstructorParameters
            ?.filter { it.hasValOrVar() && backingToken.containsMatchIn(it.typeReference?.text.orEmpty()) }
            ?.mapNotNull { it.name }
            .orEmpty()
        return (fromProperties + fromCtor).toSet()
    }

    private fun KtElement.nearestLambda(): KtLambdaExpression? =
        generateSequence(parent) { it.parent }.filterIsInstance<KtLambdaExpression>().firstOrNull()

    private fun KtElement.isBackingWrite(backing: Set<String>): Boolean = when (this) {
        is KtBinaryExpression ->
            operationToken == KtTokens.EQ &&
                (left as? KtQualifiedExpression)?.let { qualified ->
                    (qualified.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() in backing &&
                        qualified.selectorExpression?.text == "value"
                } == true

        is KtDotQualifiedExpression ->
            (receiverExpression as? KtNameReferenceExpression)?.getReferencedName() in backing &&
                (selectorExpression as? KtCallExpression)?.calleeExpression?.text in writeMethods

        else -> false
    }

    private companion object {
        val flowOperators = setOf(
            "combine", "combineTransform", "zip", "map", "mapLatest", "mapNotNull",
            "flatMapLatest", "flatMapConcat", "flatMapMerge", "transform", "transformLatest",
            "transformWhile", "onEach", "onStart", "onCompletion", "onEmpty", "onSubscription",
            "catch", "retryWhen", "scan", "runningReduce", "runningFold", "fold", "flow",
            "channelFlow", "callbackFlow", "filter", "filterNot", "filterNotNull",
            "filterIsInstance", "distinctUntilChanged", "distinctUntilChangedBy", "debounce",
            "sample", "take", "takeWhile", "drop", "dropWhile", "collect", "collectLatest",
            "collectIndexed", "first", "firstOrNull", "single", "singleOrNull", "last",
            "lastOrNull", "toList", "toSet", "toCollection", "reduce", "count", "sum", "sumOf",
            "launchIn", "produceState",
        )
        val sinkTerminals = setOf(
            "collect", "collectLatest", "collectIndexed", "launchIn", "first", "firstOrNull",
            "single", "singleOrNull", "last", "lastOrNull", "toList", "toSet", "toCollection",
            "fold", "reduce", "count", "sum", "sumOf", "asLiveData", "collectAsState",
            "collectAsStateWithLifecycle", "produceState", "consumeEach",
        )
        val writeMethods = setOf(
            "update", "updateAndGet", "getAndUpdate", "tryEmit", "emit", "compareAndSet",
            "setValue", "trySend", "send", "trySendBlocking",
        )
    }
}

internal class NoInferredMutableStatePayloadRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Public signatures must not hand a mutable payload to the UI through a flow; resolves the " +
        "payload type across files and applies the immutable-snapshot member check. " +
        "See quality/udf-contract.md.",
), RequiresAnalysisApi {
    private val optOutMarker = Regex("""behavior-contract:\s*mutable-payload-ok""")

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/") || property.isLocal || property.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        if (property.hasOptOutComment(optOutMarker)) return
        val leaks = analyze(property) {
            payloadClasses(property.symbol.returnType).any { it.mutableMemberProblems().isNotEmpty() }
        }
        if (leaks) {
            reportElement(
                property,
                "Resolved flow payload of '${property.name}' carries mutable members; the UI can " +
                    "write behind the state machine. See quality/udf-contract.md, or mark with " +
                    "'// behavior-contract: mutable-payload-ok: <reason>'.",
            )
        }
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/") || function.isLocal ||
            function.hasModifier(KtTokens.PRIVATE_KEYWORD)
        ) {
            return
        }
        if (function.hasOptOutComment(optOutMarker)) return
        val leaks = analyze(function) {
            payloadClasses(function.symbol.returnType).any { it.mutableMemberProblems().isNotEmpty() }
        }
        if (leaks) {
            reportElement(
                function,
                "Function '${function.name}' returns a flow whose resolved payload carries mutable " +
                    "members; keep the exposed stream an immutable snapshot. See " +
                    "quality/udf-contract.md, or mark with " +
                    "'// behavior-contract: mutable-payload-ok: <reason>'.",
            )
        }
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val file = parameter.containingKtFile
        if (!file.isProductionSource() || !file.path().contains("/app/src/") || !parameter.hasValOrVar() || parameter.hasModifier(KtTokens.PRIVATE_KEYWORD)) return
        if (parameter.hasOptOutComment(optOutMarker)) return
        val leaks = analyze(parameter) {
            payloadClasses(parameter.symbol.returnType).any { it.mutableMemberProblems().isNotEmpty() }
        }
        if (leaks) {
            reportElement(
                parameter,
                "Constructor property '${parameter.name}' exposes a flow whose resolved payload " +
                    "carries mutable members. See quality/udf-contract.md, or mark with " +
                    "'// behavior-contract: mutable-payload-ok: <reason>'.",
            )
        }
    }

    private fun KaSession.payloadClasses(type: KaType): List<KtClass> {
        if (!type.isSubtypeOf(flowType)) return emptyList()
        val classes = mutableListOf<KtClass>()
        collectPayloadClasses(type, classes)
        return classes
    }

    private fun KaSession.collectPayloadClasses(type: KaType, classes: MutableList<KtClass>) {
        (type.expandedSymbol?.psi as? KtClass)?.let(classes::add)
        (type as? KaClassType)?.typeArguments?.forEach { argument ->
            argument.type?.let { collectPayloadClasses(it, classes) }
        }
    }

    private companion object {
        val flowType = ClassId.fromString("kotlinx/coroutines/flow/Flow")
    }
}
