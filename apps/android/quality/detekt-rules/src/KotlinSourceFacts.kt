package com.lomo.detektrules

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtUserType

/** Import aliases preserve the imported declaration's identity. No source-text search is involved. */
internal fun KtFile.importedName(name: String): String =
    importDirectives.firstOrNull { directive ->
        directive.aliasName == name ||
            (directive.aliasName == null && directive.importedFqName?.shortName()?.asString() == name)
    }?.importedFqName?.asString() ?: name

internal fun KtCallExpression.canonicalCalleeName(): String? =
    (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()?.let { name ->
        containingKtFile.importedName(name).substringAfterLast('.')
    }

internal fun KtFile.forbiddenCodeReference(prefixes: List<String>): String? {
    var forbidden: String? = null
    fun inspect(reference: String?) {
        if (forbidden == null && reference != null && prefixes.any { prefix ->
                reference == prefix.removeSuffix(".") || reference.startsWith(prefix)
            }
        ) {
            forbidden = reference
        }
    }
    accept(object : KtTreeVisitorVoid() {
        override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
            inspect(expression.qualifiedCodeName())
            super.visitDotQualifiedExpression(expression)
        }

        override fun visitUserType(type: KtUserType) {
            inspect(type.qualifiedTypeName())
            super.visitUserType(type)
        }
    })
    return forbidden
}

private fun KtExpression.qualifiedCodeName(): String? =
    when (this) {
        is KtNameReferenceExpression -> getReferencedName()
        is KtCallExpression -> (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        is KtDotQualifiedExpression -> {
            val receiver = receiverExpression.qualifiedCodeName()
            val selector = selectorExpression?.qualifiedCodeName()
            if (receiver == null || selector == null) null else "$receiver.$selector"
        }
        else -> null
    }

private fun KtUserType.qualifiedTypeName(): String? {
    val name = referencedName ?: return null
    val parent = qualifier ?: return name
    return parent.qualifiedTypeName()?.let { "$it.$name" }
}

/**
 * Exceptions attach to a declaration/expression, never to arbitrary text somewhere in its body.
 * A catch may also document its own containment with a direct child comment inside the catch body.
 */
internal fun KtElement.hasBehaviorContractException(marker: Regex): Boolean {
    val file = containingKtFile
    val text = file.text
    var element: PsiElement? = this
    while (element != null && element !is KtFile) {
        val start = element.textRange.startOffset
        val headerEnd = (element as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset
        if (headerEnd != null && file.commentsIn(start, headerEnd).any { it.matchesException(marker) }) return true
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (file.commentsIn(lineStart, lineEnd).any { it.matchesException(marker) }) return true
        if (lineStart > 0) {
            val previousStart = text.lastIndexOf('\n', lineStart - 2) + 1
            if (file.commentsIn(previousStart, lineStart).any { comment ->
                    text.substring(previousStart, comment.textRange.startOffset).isBlank() &&
                        comment.matchesException(marker)
                }
            ) return true
        }
        element = element.parent
    }
    return this is KtCatchClause && generateSequence(catchBody?.firstChild) { it.nextSibling }
        .filterIsInstance<PsiComment>().any { it.matchesException(marker) }
}

private fun KtFile.commentsIn(start: Int, end: Int): Sequence<PsiComment> = sequence {
    var offset = start
    while (offset < end) {
        val leaf = findElementAt(offset) ?: break
        val comment = generateSequence(leaf) { it.parent }.filterIsInstance<PsiComment>().firstOrNull()
        // A token inside a preceding multiline KDoc belongs to that whole comment. Only a
        // comment that starts in this window can be attached to the current source line.
        if (comment != null && comment.textRange.startOffset in start until end) yield(comment)
        offset = maxOf(offset + 1, (comment ?: leaf).textRange.endOffset)
    }
}

private fun PsiComment.matchesException(marker: Regex): Boolean {
    val match = exceptionComment.matchEntire(text.trim()) ?: return false
    val reason = match.groupValues[2].trim()
    return marker.matches("behavior-contract: ${match.groupValues[1]}") &&
        reason.isNotBlank() && reason !in setOf("<reason>", "TODO", "FIXME")
}

private val exceptionComment =
    Regex("""//\s*behavior-contract:\s*([a-z][a-z0-9-]*):[ \t]*(\S[^\r\n]*)""")

internal class NoHandwrittenNativeDeclarationRule(config: Config) : LomoBaseRule(
    config,
    "JNI declarations belong exclusively to generated native-bindings; product Kotlin uses the data adapter.",
) {
    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        val file = function.containingKtFile
        if (!file.isProductionSource() || file.path().contains("/native-bindings/src/")) return
        if (function.hasModifier(KtTokens.EXTERNAL_KEYWORD)) {
            reportElement(function, "Handwritten external function bypasses lomo-native/native-bindings ownership.")
        }
    }
}
