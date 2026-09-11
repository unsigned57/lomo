package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

internal class ViewModelSingleStateFlowRule(
    config: Config,
) : LomoBaseRule(
    config,
    "A screen ViewModel must expose at most one in-VM built screen-state machine StateFlow<XxxState> " +
        "(combine/stateIn chain or backing asStateFlow exposure); re-exposures of dependency-owned " +
        "StateFlow<XxxState> are satellites and do not count. Mutable* state must stay private, and var is " +
        "reserved for Job cancellation handles. See docs/udf-contract.md.",
) {
    private val sessionFacadeMarker = Regex("""behavior-contract:\s*session-facade-ok""")
    private val machineFlowTypePattern =
        Regex("""(?<![A-Za-z])StateFlow<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\??\s*>\s*$""")
    private val jobTypePattern = Regex("""\bJob\b""")

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        val file = klass.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/app/src/")) return
        if (!klass.isScreenOrFacadeViewModel()) return
        if (klass.hasOptOutComment(sessionFacadeMarker)) return

        val machines = klass.machineStateFlows()
        when {
            machines.built.size > 1 -> {
                val names = machines.built.mapNotNull { it.name }.joinToString(", ")
                reportElement(
                    klass,
                    "Multiple in-VM built screen-state machine flows in ${klass.name}: found ${machines.built.size} " +
                        "($names); a screen ViewModel must expose exactly one built StateFlow<XxxState> machine " +
                        "(combine/stateIn or asStateFlow backing) and derive every other flow as a satellite " +
                        "(stateIn/combine with appWhileSubscribed()) or re-expose dependency-owned state. " +
                        "See docs/udf-contract.md, or register the session facade with " +
                        "'// behavior-contract: session-facade-ok: <reason>'.",
                )
            }

            machines.built.isEmpty() && machines.delegated.isEmpty() ->
                reportElement(
                    klass,
                    "Missing screen-state machine in ${klass.name}: a screen ViewModel must expose exactly one " +
                        "StateFlow<XxxState> machine built with combine(...)→stateIn(...), exposed from a private " +
                        "MutableStateFlow backing via asStateFlow(), or delegated from a constructor dependency. " +
                        "See docs/udf-contract.md, or register the session facade with " +
                        "'// behavior-contract: session-facade-ok: <reason>'.",
                )
        }
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/app/src/")) return
        if (property.isLocal) return

        val containingClass = property.containingClassOrObject as? KtClass ?: return
        if (!containingClass.isScreenOrFacadeViewModel()) return
        if (containingClass.hasOptOutComment(sessionFacadeMarker)) return

        val typeText = property.typeReference?.text.orEmpty()
        val initText = property.initializer?.text.orEmpty()

        val isMutableState =
            typeText.contains("MutableStateFlow") ||
                initText.contains("MutableStateFlow") ||
                typeText.contains("MutableState<") ||
                initText.contains("mutableStateOf")
        val isPrivate = property.hasModifier(KtTokens.PRIVATE_KEYWORD)
        if (isMutableState && !isPrivate) {
            reportElement(
                property,
                "Forbidden exposed mutable state '${property.name}' in ${containingClass.name}: MutableStateFlow/" +
                    "MutableState must stay private; expose the immutable screen-state machine instead. " +
                    "See docs/udf-contract.md, or register with '// behavior-contract: session-facade-ok: <reason>'.",
            )
        }

        if (property.isVar && !jobTypePattern.containsMatchIn(typeText) && !jobTypePattern.containsMatchIn(initText)) {
            reportElement(
                property,
                "Forbidden 'var' business property '${property.name}' in ${containingClass.name}: model mutable " +
                    "business state inside the screen-state machine; only Job cancellation handles may be var. " +
                    "See docs/udf-contract.md, or register with '// behavior-contract: session-facade-ok: <reason>'.",
            )
        }
    }

    private fun KtClass.isScreenOrFacadeViewModel(): Boolean =
        name?.endsWith("ViewModel") == true || superTypeListEntries.any { it.text.contains("ViewModel") }

    /**
     * Machine-shaped flows: explicitly typed StateFlow<XxxState>. Built machines carry a combine/stateIn
     * chain or an asStateFlow() backing exposure; delegated machines are pure re-exposures of a constructor
     * dependency's own state (single-sourced, no in-VM fragmentation). Body-property delegation without
     * asStateFlow (e.g. `= holder.someUiState`) is a satellite re-exposure, not a built machine.
     */
    private fun KtClass.machineStateFlows(): MachineFlows {
        val constructorDependencyNames = primaryConstructorDependencyNames()
        val candidates =
            body?.declarations
                ?.filterIsInstance<KtProperty>()
                .orEmpty()
                .mapNotNull { property ->
                    val typeText = property.typeReference?.text ?: return@mapNotNull null
                    val typeMatch = machineFlowTypePattern.find(typeText) ?: return@mapNotNull null
                    val payloadName = typeMatch.groupValues[1].substringAfterLast('.')
                    if (!payloadName.endsWith("State")) return@mapNotNull null
                    val initializer = property.initializer ?: return@mapNotNull null
                    property to initializer
                }
        val built =
            candidates
                .filter { (_, initializer) -> initializer.isBuiltMachineInitializer() }
                .map { (property, _) -> property }
        val delegated =
            candidates
                .filterNot { (_, initializer) -> initializer.isBuiltMachineInitializer() }
                .filter { (_, initializer) ->
                    constructorDependencyNames.any { name ->
                        Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(initializer.text)
                    }
                }.map { (property, _) -> property }
        return MachineFlows(built = built, delegated = delegated)
    }

    private data class MachineFlows(
        val built: List<KtProperty>,
        val delegated: List<KtProperty>,
    )

    private fun KtClass.primaryConstructorDependencyNames(): Set<String> =
        getPrimaryConstructor()
            ?.valueParameters
            ?.mapNotNull { parameter -> parameter.name }
            .orEmpty()
            .toSet()

    private fun KtExpression.isBuiltMachineInitializer(): Boolean {
        val text = text
        return text.contains("combine(") || text.contains("stateIn(") || text.contains(".asStateFlow(")
    }
}

internal class NoInSituRevisionBypassRule(
    config: Config,
) : LomoBaseRule(
    config,
    "In-situ snapshot fetching before mutations (TOCTOU) is forbidden; expectedRevision must originate from the UI edit session baseline.",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*in-situ-read-ok""")

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return

        val body = function.bodyExpression ?: return
        val bodyText = body.text

        // Check if the function constructs a StoreMemoCommand or applies mutations
        val hasMutation = bodyText.contains("StoreMemoCommand") ||
            bodyText.contains("applyMemoCommand") ||
            bodyText.contains("startWorkspaceDocumentCommand") ||
            bodyText.contains("WorkspaceNativeExpectedState.Match")

        if (!hasMutation) return

        if (function.hasOptOutComment(optOutMarker)) return

        // Detect in-situ read patterns: reading snapshot right inside mutation function
        val inSituReadPatterns = listOf(
            Regex("""(?:val|var)\s+(\w+)\s*=\s*(?:port|queryRepository|adapter)\.(?:getMemo|requireMemoSnapshot|queryMemos)"""),
            Regex("""(?:val|var)\s+(\w+)\s*=\s*.*?\.(?:getMemo|requireMemoSnapshot)"""),
        )

        for (pattern in inSituReadPatterns) {
            val match = pattern.find(bodyText)
            if (match != null) {
                val varName = match.groupValues[1]
                val assignsRevision = bodyText.contains("expectedRevision = $varName") ||
                    bodyText.contains("expectedRevision = snap") ||
                    bodyText.contains("expectedFingerprint = $varName") ||
                    bodyText.contains("expectedFingerprint = snap") ||
                    bodyText.contains("Match($varName") ||
                    bodyText.contains("expectedState = WorkspaceNativeExpectedState.Match($varName")

                if (assignsRevision) {
                    reportElement(
                        function,
                        "Optimistic concurrency bypass (Audit U2 / TOCTOU) in '${function.name}': " +
                            "calling '${match.value.trim()}' and piping its revision/fingerprint into mutation expectedRevision. " +
                            "Mutations must verify against the caller's session baseline, not an on-the-spot re-read. " +
                            "Pass expectedRevision/expectedFingerprint from caller or document with '// behavior-contract: in-situ-read-ok: <reason>'.",
                    )
                    break
                }
            }
        }
    }
}
