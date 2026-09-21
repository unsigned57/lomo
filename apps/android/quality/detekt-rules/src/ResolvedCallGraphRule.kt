package com.lomo.detektrules

import dev.detekt.api.Config
import dev.detekt.api.RequiresAnalysisApi
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Emits compiler-resolved call identities for the architecture-owned foreign capability check. */
@OptIn(KaExperimentalApi::class)
internal class ResolvedCallGraphRule(config: Config) : LomoBaseRule(
    config,
    "Foreign export reachability uses resolved calls, override edges and explicit platform callbacks.",
), RequiresAnalysisApi {
    private val edges = sortedSetOf<Pair<String, String>>(compareBy({ it.first }, { it.second }))
    private val roots = sortedSetOf<String>()
    private val declarations = sortedSetOf<String>()

    override fun visitKtFile(file: KtFile) {
        if (!file.isProductionSource()) return
        edges.clear()
        roots.clear()
        declarations.clear()
        super.visitKtFile(file)
        val outputRoot = System.getenv("LOMO_SYMBOL_FACTS_DIR") ?: return
        val path = Path.of(file.path())
        val digest = sha256(Files.readAllBytes(path))
        val output = Path.of(outputRoot, sha256(file.path().toByteArray()) + ".json")
        Files.createDirectories(output.parent)
        Files.writeString(
            output,
            """{"schema_version":1,"path":${quoted(file.path())},"source_digest":${quoted(digest)},"declarations":${strings(declarations)},"roots":${strings(roots)},"edges":[${edges.joinToString(",") { "[${quoted(it.first)},${quoted(it.second)}]" }}]}""",
        )
        writeGeneratedDeclarations(file, Path.of(outputRoot))
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val id = identity(function)
        declarations.add(id)
        analyze(function) {
            function.symbol.allOverriddenSymbols.forEach { overridden ->
                val base = symbolIdentity(overridden) ?: return@forEach
                edges.add(base to id)
                if (!base.startsWith("com.lomo.") || base.startsWith("com.lomo.nativebridge.")) {
                    // Framework and generated callback interfaces are explicit host entry points.
                    roots.add(id)
                }
            }
        }
        if (function.hasModifier(KtTokens.OPERATOR_KEYWORD) && function.name == "invoke") {
            function.getStrictParentOfType<KtClassOrObject>()?.fqName?.asString()?.let { owner -> edges.add(owner to id) }
        }
    }

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        if (property.isLocal) return
        val id = identity(property)
        declarations.add(id)
        property.getStrictParentOfType<KtClassOrObject>()?.fqName?.asString()?.let { owner -> edges.add(owner to id) }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val from = enclosingIdentity(expression) ?: return
        val target = analyze(expression) {
            expression.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol?.symbol?.let(::symbolIdentity)
        } ?: return
        edges.add(from to target)
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        super.visitSimpleNameExpression(expression)
        val from = enclosingIdentity(expression) ?: return
        val target = analyze(expression) {
            expression.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol?.symbol?.let(::symbolIdentity)
        } ?: return
        edges.add(from to target)
    }

    override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
        super.visitCallableReferenceExpression(expression)
        val from = enclosingIdentity(expression) ?: return
        val target = analyze(expression) {
            expression.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol?.symbol?.let(::symbolIdentity)
        } ?: return
        edges.add(from to target)
    }

    private fun enclosingIdentity(element: org.jetbrains.kotlin.psi.KtElement): String? {
        val declaration = generateSequence(element.parent) { it.parent }
            .filterIsInstance<KtCallableDeclaration>()
            .firstOrNull { it is KtNamedFunction || (it is KtProperty && !it.isLocal) }
        return declaration?.let(::identity)
            ?: element.getStrictParentOfType<KtClassOrObject>()?.fqName?.asString()
    }

    private fun identity(declaration: KtCallableDeclaration): String = analyze(declaration) {
        val symbol = when (declaration) {
            is KtNamedFunction -> declaration.symbol
            is KtProperty -> declaration.symbol
            else -> null
        }
        symbol?.let(::symbolIdentity) ?: "${declaration.containingKtFile.path()}#${declaration.textOffset}"
    }

    private fun symbolIdentity(symbol: KaCallableSymbol): String? =
        if (symbol is KaConstructorSymbol) symbol.containingClassId?.asSingleFqName()?.asString()
        else symbol.callableId?.asSingleFqName()?.asString()

    private fun writeGeneratedDeclarations(file: KtFile, directory: Path) {
        val generatedSource = System.getenv("LOMO_BINDINGS_SOURCE") ?: return
        val destination = directory.resolve("generated.json")
        if (Files.exists(destination)) return
        val source = Path.of(generatedSource)
        val parsed = KtPsiFactory(file.project).createFile(Files.readString(source))
        val functions = parsed.collectDescendantsOfType<KtNamedFunction>()
            .filter { !it.hasModifier(KtTokens.PRIVATE_KEYWORD) }
            .mapNotNull { it.fqName?.asString() }.toSortedSet()
        Files.writeString(destination, """{"schema_version":1,"path":${quoted(source.toString())},"source_digest":${quoted(sha256(Files.readAllBytes(source)))},"declarations":${strings(functions)}}""")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun strings(values: Collection<String>): String = values.joinToString(",", "[", "]", transform = ::quoted)
    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
