package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parents

/*
 * Audit-derived first-principles invariants (audit/01-03). Each rule description names its
 * invariant and audit anchor; markers use the declared `behavior-contract` vocabulary.
 */

internal class NoMintedIdentityRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Identity fields (operationId, sessionId, *Id/*Token/*Epoch/*Generation/*Fence/*Revision/" +
        "*Nonce/*Secret/*Key/*Fingerprint/*Digest) must come from their owner, not from " +
        "UUID/Random/hash/clock mints at call sites (audit I1: A04, B03, B04, C09).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*identity-mint-ok""")

    private val mintCalleeNames =
        setOf(
            "randomUUID", "currentTimeMillis", "nanoTime",
            "nextInt", "nextLong", "nextBytes", "nextDouble", "nextFloat", "nextBoolean", "nextChar",
        )
    // Content-derived hashes are measured, not forged — they only violate the invariant when
    // they feed sequencing identity (a digest can never be a generation/fence/revision).
    private val derivedCalleeNames = setOf("hashCode", "md5Hex", "sha256Hex", "sha256", "md5", "digest")
    private val mintQualifiedRoots = setOf("UUID", "java.util.UUID", "SecureRandom", "ThreadLocalRandom")
    private val wireTypeSuffixes = listOf("Request", "Command", "Intent", "Envelope")

    private enum class MintKind { NONE, FORGED, MEASURED }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val callee = expression.calleeExpression?.text.orEmpty()
        val isWireConstructor = wireTypeSuffixes.any(callee::endsWith)

        for (argument in expression.valueArguments) {
            val argExpr = argument.getArgumentExpression() ?: continue
            val argName = argument.getArgumentName()?.asName?.identifier
            val kind = argExpr.mintKind()
            val identityTarget = when {
                argName != null ->
                    isIdentityName(argName) &&
                        (kind == MintKind.FORGED || (kind == MintKind.MEASURED && isSequencingName(argName)))
                else -> isWireConstructor && kind == MintKind.FORGED
            }
            if (!identityTarget) continue
            if (argExpr.isInsideMintingAuthority()) continue
            if (argument.hasOptOutComment(optOutMarker)) continue
            reportElement(
                argument,
                "Identity minted at call site into '${argName ?: callee}': $argExpr. " +
                    "Identity must be minted by its owner and threaded in (audit I1). " +
                    "If this site is the owner, mark '// behavior-contract: identity-mint-ok: <reason>'.",
            )
        }
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return

        val name = property.name ?: return
        if (!isIdentityName(name)) return
        val initializer = property.initializer ?: return
        val kind = initializer.mintKind()
        if (kind == MintKind.NONE || (kind == MintKind.MEASURED && !isSequencingName(name))) return
        if (initializer.isInsideMintingAuthority()) return
        if (property.hasOptOutComment(optOutMarker)) return
        reportElement(
            property,
            "Identity '$name' minted from a random/hash/clock source: ${initializer.text}. " +
                "Mint identity at its owner and thread it in (audit I1), or mark " +
                "'// behavior-contract: identity-mint-ok: <reason>'.",
        )
    }

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val file = parameter.containingKtFile
        if (!file.isProductionSource()) return

        val name = parameter.name ?: return
        if (!isIdentityName(name)) return
        val defaultValue = parameter.defaultValue ?: return
        val kind = defaultValue.mintKind()
        if (kind == MintKind.NONE || (kind == MintKind.MEASURED && !isSequencingName(name))) return
        if (defaultValue.isInsideMintingAuthority()) return
        if (parameter.hasOptOutComment(optOutMarker)) return
        reportElement(
            parameter,
            "Identity '$name' defaults to a minted value: ${defaultValue.text}. " +
                "Identity must arrive from the owner (audit I1), or mark " +
                "'// behavior-contract: identity-mint-ok: <reason>'.",
        )
    }

    private fun isIdentityName(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "id" ||
            lower.endsWith("id") ||
            isSequencingName(name) ||
            lower.endsWith("secret") ||
            lower.endsWith("key") ||
            lower.endsWith("fingerprint") ||
            lower.endsWith("digest")
    }

    // Sequencing identity orders events; a content digest can never mint one — it only measures.
    private fun isSequencingName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith("token") ||
            lower.endsWith("epoch") ||
            lower.endsWith("generation") ||
            lower.endsWith("fence") ||
            lower.endsWith("revision") ||
            lower.endsWith("version") ||
            lower.endsWith("nonce")
    }

    private fun KtExpression.mintKind(): MintKind {
        val kinds = collectDescendantsOfType<KtCallExpression>()
            .plus(if (this is KtCallExpression) sequenceOf(this) else emptySequence())
            .mapNotNull(::mintCallKind)
            .toList()
        return when {
            MintKind.FORGED in kinds -> MintKind.FORGED
            MintKind.MEASURED in kinds -> MintKind.MEASURED
            else -> MintKind.NONE
        }
    }

    private fun mintCallKind(call: KtExpression?): MintKind? {
        val expression = call as? KtCallExpression ?: return null
        val callee = expression.calleeExpression?.text ?: return null
        if (callee in derivedCalleeNames) return MintKind.MEASURED
        if (callee == "randomUUID") return MintKind.FORGED
        if (callee in mintCalleeNames) {
            val qualified = (expression.parent as? KtDotQualifiedExpression)?.takeIf { it.selectorExpression == expression }
            val receiverText = qualified?.receiverExpression?.text.orEmpty()
            return if (receiverText.contains("Random") || receiverText in mintQualifiedRoots ||
                receiverText.endsWith(".System") || receiverText == "System"
            ) {
                MintKind.FORGED
            } else {
                null
            }
        }
        if (callee == "SecureRandom" || callee == "ThreadLocalRandom" || callee == "Random") {
            val isRandomCtor = expression.valueArguments.isEmpty() ||
                (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text == "java.util"
            return if (isRandomCtor) MintKind.FORGED else null
        }
        return null
    }

    // Owner-controlled mints are legal: canonical minting declarations (new-/mint-/next-/issue-
    // prefixed functions, properties, parameters, or owner types) and injectable mint seams
    // (a `() -> T` provider/factory lambda default).
    private fun KtExpression.isInsideMintingAuthority(): Boolean {
        val ownerNamed = parents
            .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedDeclaration>()
            .any { declaration -> declaration.name?.let(::isMintOwnerName) == true }
        if (ownerNamed) return true
        val lambda = parents.filterIsInstance<KtLambdaExpression>().firstOrNull() ?: return false
        val lambdaOwner = lambda.parents.firstOrNull { it is KtParameter || it is KtProperty }
        val typeText = when (lambdaOwner) {
            is KtParameter -> lambdaOwner.typeReference?.text
            is KtProperty -> lambdaOwner.typeReference?.text
            else -> null
        }
        return typeText?.contains("->") == true
    }

    private val mintOwnerPrefixes = listOf("new", "mint", "next", "issue")

    private fun isMintOwnerName(name: String): Boolean =
        mintOwnerPrefixes.any { prefix ->
            name.startsWith(prefix, ignoreCase = true) &&
                name.drop(prefix.length).firstOrNull()?.let { it.isUpperCase() || it == '_' } == true
        }
}

internal class NoErrorMessageControlFlowRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Error disposition must come from typed codes, never from matching diagnostic message text " +
        "(audit I2: B01 message-string classification, conflict_session_missing special-casing).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*error-text-ok""")

    // A diagnostic read is either a qualified property access (`error.message`) or a bare
    // lowercase-start word (`detail`, `rawMessage`). Capitalized selectors (`SettingsOperationError.Message`)
    // and call shapes (`toUserMessage(...)`) produce error text; they don't consume it.
    private val qualifiedDiagnostic =
        Regex("""\.(message|localizedMessage|diagnostic|errorMessage|detail|description)\b(?!\s*\()""")
    private val diagnosticWord =
        Regex("""(?i)\b\w*(message|localizedmessage|diagnostic|errormessage|detail|description)\b(?!\s*\()""")
    private val decisionCalls =
        setOf("contains", "startsWith", "endsWith", "matches", "regionMatches", "indexOf", "equals", "compareTo")
    private val decoderName =
        Regex("""(?i)\w*(error|failure)\w*(from|to)(message|text|string|diagnostic|description)\w*""")

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource()) return
        val name = function.name ?: return
        if (decoderName.matches(name) && !function.hasOptOutComment(optOutMarker)) {
            reportNamedDeclaration(
                function,
                "Decoder '$name' derives error identity from message text. Return typed error codes " +
                    "from the producing boundary instead (audit I2).",
            )
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in decisionCalls) return
        if (!expression.isInDecisionPosition()) return

        val qualified = expression.parent as? org.jetbrains.kotlin.psi.KtQualifiedExpression
        val receiver = qualified?.takeIf { it.selectorExpression == expression }?.receiverExpression
        if (receiver != null && !isDiagnosticText(receiver, expression)) return
        if (receiver == null) return
        if (expression.hasOptOutComment(optOutMarker)) return
        reportElement(
            expression,
            "Control flow decided by diagnostic message text ('${receiver.text.trim()}'). " +
                "Error disposition must come from typed codes (audit I2). If a platform API offers " +
                "no typed signal, mark '// behavior-contract: error-text-ok: <reason>'.",
        )
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        if (expression.operationToken != KtTokens.EQEQ && expression.operationToken != KtTokens.EXCLEQ) return
        if (!expression.isInDecisionPosition()) return

        val left = expression.left
        val right = expression.right
        val literalSide = listOf(left, right).firstOrNull { it is KtStringTemplateExpression }
        val textSide = listOf(left, right).firstOrNull { it != null && it !is KtStringTemplateExpression }
        if (literalSide == null || textSide == null) return
        if (!isDiagnosticText(textSide, expression)) return
        if (expression.hasOptOutComment(optOutMarker)) return
        reportElement(
            expression,
            "Error text compared against a literal in control flow. Disposition must come from " +
                "typed codes (audit I2), or mark '// behavior-contract: error-text-ok: <reason>'.",
        )
    }

    override fun visitWhenExpression(expression: KtWhenExpression) {
        super.visitWhenExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        val subject = expression.subjectExpression ?: return
        // A bare `when (message)`/`when (detail)` subject is usually sealed-type dispatch; the
        // diagnostic shape is a qualified `.message`/`.detail` read or an alias bound to one.
        val subjectText = subject.text.trim()
        val diagnostic = qualifiedDiagnostic.containsMatchIn(subjectText) ||
            isDiagnosticAlias(subject, expression)
        if (!diagnostic) return
        if (expression.hasOptOutComment(optOutMarker)) return
        reportElement(
            subject,
            "when() branches on diagnostic message text. Disposition must come from typed codes " +
                "(audit I2), or mark '// behavior-contract: error-text-ok: <reason>'.",
        )
    }

    private fun org.jetbrains.kotlin.psi.KtElement.isInDecisionPosition(): Boolean =
        parents.any { ancestor ->
            when (ancestor) {
                is KtIfExpression -> ancestor.condition != null &&
                    ancestor.condition!!.textRange.contains(textRange)
                is KtWhenConditionWithExpression -> true
                is KtWhileExpression -> ancestor.condition?.textRange?.contains(textRange) == true
                is KtDoWhileExpression -> ancestor.condition?.textRange?.contains(textRange) == true
                else -> false
            }
        }

    private fun isDiagnosticText(expression: KtExpression, context: org.jetbrains.kotlin.psi.KtElement): Boolean =
        expression.text.trim().containsDiagnosticRead() || isDiagnosticAlias(expression, context)

    private fun String.containsDiagnosticRead(): Boolean =
        qualifiedDiagnostic.containsMatchIn(this) ||
            diagnosticWord.findAll(this).any { match -> match.value.first().isLowerCase() }

    private fun isDiagnosticAlias(
        expression: KtExpression,
        context: org.jetbrains.kotlin.psi.KtElement,
    ): Boolean {
        val bareName = (expression as? KtNameReferenceExpression)?.getReferencedName()
            ?: return false
        val function = context.parents.filterIsInstance<KtNamedFunction>().firstOrNull() ?: return false
        val alias = function.bodyExpression
            ?.collectDescendantsOfType<KtProperty>()
            ?.firstOrNull { it.isLocal && it.name == bareName }
            ?: return false
        return alias.initializer?.text?.containsDiagnosticRead() == true
    }
}

internal class NoPlaceholderCollaboratorRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Production source must not declare NoOp/Disabled/Empty/Stub/Fake/Dummy implementations of " +
        "capability interfaces; a placeholder silently drops facts (audit I4: T57 " +
        "NoOpMemoSnapshotPreferencesRepository).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*placeholder-ok""")
    private val placeholderName = Regex("""(?i)^(noop|disabled|empty|stub|fake|dummy|throwaway)\w*$""")

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)
        val file = classOrObject.containingKtFile
        if (!file.isProductionSource()) return
        val name = classOrObject.name ?: return
        if (!placeholderName.matches(name)) return
        val capabilitySupertype = classOrObject.superTypeListEntries
            .mapNotNull { it.typeReference?.text?.trim() }
            .firstOrNull(::isCapabilityType)
            ?: return
        if (classOrObject.hasOptOutComment(optOutMarker)) return
        reportDeclaration(
            classOrObject,
            "Placeholder collaborator '$name' implements capability '$capabilitySupertype': a silent " +
                "stand-in drops facts instead of surfacing missing wiring (audit I4). Wire the real " +
                "collaborator or delete the placeholder; if genuinely needed, mark " +
                "'// behavior-contract: placeholder-ok: <reason>'.",
        )
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val file = property.containingKtFile
        if (!file.isProductionSource()) return
        val name = property.name ?: return
        if (!placeholderName.matches(name)) return
        val objectExpression = property.initializer as? KtObjectLiteralExpression ?: return
        val capabilitySupertype = objectExpression.objectDeclaration.superTypeListEntries
            .mapNotNull { it.typeReference?.text?.trim() }
            .firstOrNull(::isCapabilityType)
            ?: return
        if (property.hasOptOutComment(optOutMarker)) return
        reportNamedDeclaration(
            property,
            "Placeholder collaborator '$name' implements capability '$capabilitySupertype' (audit I4). " +
                "Wire the real collaborator, or mark '// behavior-contract: placeholder-ok: <reason>'.",
        )
    }

    private fun isCapabilityType(typeText: String): Boolean =
        capabilitySuffixes.any { suffix -> typeText.substringAfterLast('.').endsWith(suffix) }

    companion object {
        internal val capabilitySuffixes =
            listOf(
                "Repository", "UseCase", "Store", "Service", "Port", "Gateway", "Client",
                "Provider", "Tracker", "Scheduler", "Validator", "Source", "Coordinator",
                "Factory", "Engine", "Bridge", "Adapter", "Executor", "Manager", "Registry",
                "Policy", "DataSource", "Dao", "Bus", "Handler", "Resolver", "Checker",
                "Planner", "Transport", "Ledger", "Notifier",
            )
    }
}

internal class NoCapabilitySeamRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Capability collaborators must be required, not optional: no nullable capability constructor " +
        "parameters, no NoOp/null defaults, and no getOrNull() optional resolution in DI modules " +
        "(audit I4: T57 capability seams).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*capability-seam-ok""")
    private val placeholderValue = Regex("""(?i)^(noop|disabled|empty|stub|fake|dummy)\w*$""")

    override fun visitParameter(parameter: KtParameter) {
        super.visitParameter(parameter)
        val file = parameter.containingKtFile
        if (!file.isProductionSource()) return
        val constructor = parameter.parent.parent
        if (constructor !is KtPrimaryConstructor && constructor !is KtSecondaryConstructor) return

        val typeText = parameter.typeReference?.text?.trim() ?: return
        val isNullable = typeText.endsWith("?")
        val bareType = typeText.trimEnd('?').trim()
        val capability = NoPlaceholderCollaboratorRule.capabilitySuffixes
            .any { suffix -> bareType.substringAfterLast('.').endsWith(suffix) }
        if (!capability) return

        val ownerName = (constructor as? KtPrimaryConstructor)
            ?.getContainingClassOrObject()
            ?.name
            .orEmpty()
        val isDataClassOwner = (constructor as? KtPrimaryConstructor)
            ?.getContainingClassOrObject()
            ?.let { (it as? KtClass)?.isData() == true } == true
        // A data class models state; a nullable field is data. The exception is the
        // dependencies-carrier shape, where a null default is a wiring seam.
        val isDependencyCarrier = ownerName.matches(Regex(""".*(Dependencies|Deps|Bindings|Graph)$"""))

        val default = parameter.defaultValue
        val placeholderDefault = default != null &&
            (default is KtObjectLiteralExpression || placeholderValue.matches(default.text.trim()))

        if (parameter.hasOptOutComment(optOutMarker)) return
        when {
            placeholderDefault ->
                reportElement(
                    parameter,
                    "Capability '${parameter.name}' defaults to a placeholder '${default.text}'. " +
                        "Inject the real collaborator at the composition root (audit I4), or mark " +
                        "'// behavior-contract: capability-seam-ok: <reason>'.",
                )
            isNullable && !isDataClassOwner ->
                reportElement(
                    parameter,
                    "Capability '${parameter.name}: $typeText' is nullable: the class silently tolerates " +
                        "missing wiring and degrades to a no-op path (audit I4). Require the collaborator, " +
                        "or mark '// behavior-contract: capability-seam-ok: <reason>'.",
                )
            isNullable && isDependencyCarrier && default != null && default.text.trim() == "null" ->
                reportElement(
                    parameter,
                    "Capability '${parameter.name}: $typeText' defaults to null inside a dependencies " +
                        "carrier (audit I4). Require the collaborator, or mark " +
                        "'// behavior-contract: capability-seam-ok: <reason>'.",
                )
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/di/")) return
        if (expression.calleeExpression?.text != "getOrNull") return
        if (expression.valueArguments.isNotEmpty()) return
        val qualified = expression.parent as? KtDotQualifiedExpression
        if (qualified?.selectorExpression == expression && qualified.receiverExpression.text.isNotBlank()) return
        if (expression.hasOptOutComment(optOutMarker)) return
        reportElement(
            expression,
            "getOrNull() inside a DI module silently tolerates an unbound capability (audit I4). " +
                "Resolve with get() so missing bindings fail fast, or mark " +
                "'// behavior-contract: capability-seam-ok: <reason>'.",
        )
    }
}

internal class NoSecretInWorkPayloadRule(
    config: Config,
) : LomoBaseRule(
    config,
    "WorkManager payloads persist plaintext; they may carry secret field NAMES but never secret " +
        "values (audit I6: B15 credential material in WorkData).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*secret-payload-ok""")

    private val secretToken =
        Regex("""(?i).*(password|passwd|secret|credential|token|privatekey|accesskey|apikey|authtoken|bearer|sessionkey|encryptionkey).*""")
    private val indirectionSuffix =
        Regex("""(?i).*(fieldkey|fieldname|keyname|keyalias|alias|ref|handle|id|name)$""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        val importsWork = file.importPaths().any { it.startsWith("androidx.work") }
        if (!importsWork && !file.path().contains("/worker/")) return

        val callee = expression.calleeExpression?.text ?: return
        val valueExpressions = mutableListOf<KtExpression>()
        when {
            callee == "workDataOf" ->
                expression.valueArguments.forEach { argument ->
                    val argExpr = argument.getArgumentExpression() ?: return@forEach
                    when {
                        argExpr is KtBinaryExpression && argExpr.operationReference.text == "to" ->
                            argExpr.right?.let(valueExpressions::add)
                        argExpr is KtCallExpression && argExpr.calleeExpression?.text == "Pair" ->
                            argExpr.valueArguments.getOrNull(1)?.getArgumentExpression()?.let(valueExpressions::add)
                    }
                }
            callee.startsWith("put") && expression.valueArguments.size >= 2 -> {
                val named = expression.valueArguments.firstOrNull {
                    it.getArgumentName()?.asName?.identifier == "value"
                }
                val value = named?.getArgumentExpression()
                    ?: expression.valueArguments.getOrNull(1)?.getArgumentExpression()
                value?.let(valueExpressions::add)
            }
        }

        for (value in valueExpressions) {
            val offender = value.collectDescendantsOfType<KtNameReferenceExpression>()
                .plus(if (value is KtNameReferenceExpression) sequenceOf(value) else emptySequence())
                .firstOrNull { reference ->
                    val identifier = reference.getReferencedName()
                    secretToken.matches(identifier) && !indirectionSuffix.matches(identifier)
                }
            if (offender == null) continue
            if (expression.hasOptOutComment(optOutMarker)) continue
            reportElement(
                expression,
                "WorkManager payload carries a secret-bearing value '${offender.getReferencedName()}': " +
                    "WorkData persists plaintext, so only field names/keys may cross it (audit I6). " +
                    "Resolve material inside the worker, or mark '// behavior-contract: secret-payload-ok: <reason>'.",
            )
        }
    }
}

internal class NoNamePredicateDeleteRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Destructive operations must select targets by exact identity, never by fuzzy name predicates " +
        "like it.name.contains(key) (audit I8: A05 fuzzy deletion).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*fuzzy-delete-ok""")

    private val fuzzyCalls = setOf("contains", "startsWith", "endsWith", "matches", "containsMatchIn", "find")
    private val nameReceiver = Regex("""(?i)(^|\.)(name|filename|namewithoutextension|filenamewithoutextension|displayname|basename)$""")
    private val destructiveCallees =
        setOf(
            "delete", "deleteIfExists", "deleteRecursively", "deleteIfExistsRecursively",
            "unlink", "removeIf", "removeAll", "retainAll", "remove", "deleteFile", "moveToTrash",
        )
    // A destructive context is a call site (`removeIf`, `delete`) or a declaration whose name
    // *begins* with a destructive verb. Query-shaped names (`walkTrashMarkdownFiles`,
    // `listTrashFiles`) enumerate a set; deletion still uses the returned exact handles.
    private val destructiveContextName =
        Regex("""(?i)(delete|remove|trash|purge|wipe|unlink|destroy|clean|clear|prune|evict|drop|moveToTrash|moveToBin|empty)\w*""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in fuzzyCalls) return

        val qualified = expression.parent as? org.jetbrains.kotlin.psi.KtQualifiedExpression ?: return
        if (qualified.selectorExpression != expression) return
        val receiverText = qualified.receiverExpression.text.trim()
        if (!nameReceiver.containsMatchIn(receiverText)) return

        if (!hasDestructiveContext(expression)) return
        if (expression.hasOptOutComment(optOutMarker)) return
        reportElement(
            expression,
            "Fuzzy name predicate '$receiverText.$callee(...)' selects a destructive-operation target. " +
                "Deletion must use exact identity (audit I8). If the fuzzy match is provably safe, mark " +
                "'// behavior-contract: fuzzy-delete-ok: <reason>'.",
        )
    }

    private fun hasDestructiveContext(expression: KtExpression): Boolean =
        expression.parents.any { ancestor ->
            when (ancestor) {
                is KtCallExpression ->
                    ancestor.calleeExpression?.text in destructiveCallees
                is KtNamedFunction ->
                    ancestor.name?.let { destructiveContextName.matches(it) } == true
                is KtProperty ->
                    ancestor.name?.let { destructiveContextName.matches(it) } == true
                else -> false
            }
        }
}

internal class NoCorruptionEmptyResetRule(
    config: Config,
) : LomoBaseRule(
    config,
    "A corruption handler must not silently reset durable state to empty; corruption is a typed " +
        "failure to surface, not a zero-fill (audit I2: C15 emptyPreferences reset).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*corruption-reset-ok""")
    private val emptyFactories = setOf("emptyPreferences", "emptyMap", "emptyList", "emptySet", "emptySequence")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return
        if (expression.calleeExpression?.text != "ReplaceFileCorruptionHandler") return
        if (expression.hasOptOutComment(optOutMarker)) return

        val lambda = expression.lambdaArguments.firstOrNull()?.getLambdaExpression()
            ?: (expression.valueArguments.singleOrNull()?.getArgumentExpression() as? KtLambdaExpression)
            ?: return
        val body = lambda.functionLiteral.bodyBlockExpression ?: return
        val resets = body.statements.isNotEmpty() && body.statements.all { it.isEmptyReset() }
        if (!resets) return
        reportElement(
            expression,
            "ReplaceFileCorruptionHandler returns empty state: corruption silently wipes durable " +
                "preferences (audit I2). Surface the CorruptionException or recover real state; if " +
                "the store is disposable cache, mark '// behavior-contract: corruption-reset-ok: <reason>'.",
        )
    }

    private fun KtExpression.isEmptyReset(): Boolean {
        if (this is KtConstantExpression) return text.trim() in setOf("null", "false", "0")
        if (this is KtCallExpression) return calleeExpression?.text in emptyFactories
        if (this is KtDotQualifiedExpression) {
            val selector = selectorExpression
            return selector is KtCallExpression && selector.calleeExpression?.text in emptyFactories
        }
        if (this is org.jetbrains.kotlin.psi.KtReturnExpression) {
            return returnedExpression?.isEmptyReset() == true
        }
        return false
    }
}

internal class NoDomainClockRule(
    config: Config,
) : LomoBaseRule(
    config,
    "domain must not read the platform wall clock or timezone inline; time enters as a value or an " +
        "injected provider seam (audit I2/C09: dual time authority, RemoteSyncConflictDialogUseCase clock).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*domain-clock-ok""")

    private val clockRead =
        Regex(
            """(?i)(system\.currenttimemillis|system\.nanotime|instant\.now|localdate\.now|""" +
                """localdatetime\.now|localtime\.now|zoneddatetime\.now|offsetdatetime\.now|""" +
                """offsettime\.now|calendar\.getinstance|systemdefault|clock\.system)""",
        )

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        check(expression, expression.text)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text == "Date" && expression.valueArguments.isEmpty()) {
            check(expression, expression.text)
        }
    }

    private fun check(element: org.jetbrains.kotlin.psi.KtElement, text: String) {
        val file = element.containingKtFile
        if (!file.isProductionSource()) return
        if (!file.path().contains("/domain/src/")) return
        if (!clockRead.containsMatchIn(text)) return
        if (element.isInsideClockSeam()) return
        if (element.hasOptOutComment(optOutMarker)) return
        reportElement(
            element,
            "domain reads the platform wall clock inline: '$text'. Time is a domain input — thread " +
                "it as a value or an injected provider seam (audit C09). If the read is structural, " +
                "mark '// behavior-contract: domain-clock-ok: <reason>'.",
        )
    }

    private fun org.jetbrains.kotlin.psi.KtElement.isInsideClockSeam(): Boolean {
        val lambda = parents.filterIsInstance<KtLambdaExpression>().firstOrNull() ?: return false
        val lambdaOwner = lambda.parents.firstOrNull { it is KtParameter || it is KtProperty }
        val typeText = when (lambdaOwner) {
            is KtParameter -> lambdaOwner.typeReference?.text
            is KtProperty -> lambdaOwner.typeReference?.text
            else -> null
        }
        return typeText?.contains("->") == true
    }
}

internal class NoConstantStatusValueRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Measured status fields (progress/percent/ratio/fraction/confidence) must receive measured " +
        "values, not literal constants; a constant status is a dead surface (audit I4: B05 " +
        "getStatus, B11 Transferring(0f)).",
) {
    private val optOutMarker = Regex("""behavior-contract:\s*constant-status-ok""")

    private val measuredNames = setOf("progress", "percent", "ratio", "fraction", "confidence", "completionRatio")
    // In-flight status constructors (…Transferring/…Downloading/…) measure live work; positional
    // literals are dead surfaces there. Model types like `*Progress` are exempt at the callee
    // level — their `progress`-named arguments still flag via `measuredNames`.
    private val statusCallee = Regex("""(?i).*(transferring|downloading|uploading|syncing|buffering)$""")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isProductionSource()) return

        val callee = expression.calleeExpression?.text.orEmpty()
        val statusCtor = statusCallee.matches(callee)
        for (argument in expression.valueArguments) {
            val argExpr = argument.getArgumentExpression() ?: continue
            if (argExpr !is KtConstantExpression) continue
            if (!argExpr.isNumericLiteral()) continue
            val namedMeasured = argument.getArgumentName()?.asName?.identifier in measuredNames
            if (!namedMeasured && !statusCtor) continue
            if (argument.hasOptOutComment(optOutMarker)) continue
            reportElement(
                argument,
                "Measured status field fed literal '${argExpr.text}': a constant progress/status is a " +
                    "dead surface (audit I4/B11). Emit the measured value, or mark " +
                    "'// behavior-contract: constant-status-ok: <reason>'.",
            )
        }
    }

    private fun KtConstantExpression.isNumericLiteral(): Boolean =
        text.trim().matches(Regex("""-?\d+(\.\d+)?[fFlL]?"""))
}
