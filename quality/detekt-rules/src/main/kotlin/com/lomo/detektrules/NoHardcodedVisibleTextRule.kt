package com.lomo.detektrules

import com.intellij.psi.PsiElement
import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

internal class NoHardcodedVisibleTextRule(
    config: Config,
) : LomoBaseRule(
    config,
    "User-visible Kotlin text must come from Android string resources so shipped locales stay complete.",
) {
    private val sourcePathFragments =
        config.valueOrDefault(
            "sourcePathFragments",
            listOf(
                "/app/src/",
                "/ui-components/src/",
            ),
        )
    private val textCallees = config.valueOrDefault("textCallees", listOf("Text", "BasicText", "ClickableText")).toSet()
    private val contentDescriptionArgumentNames =
        config.valueOrDefault("contentDescriptionArgumentNames", listOf("contentDescription")).toSet()
    private val androidTextSetterCallees =
        config.valueOrDefault(
            "androidTextSetterCallees",
            listOf(
                "setTitle",
                "setSubtitle",
                "setMessage",
                "setText",
                "setPositiveButtonText",
                "setNegativeButtonText",
                "setNeutralButtonText",
            ),
        ).toSet()

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        val file = expression.containingKtFile
        if (!file.isVisibleTextSource()) return

        val literalText = expression.localizableLiteralText()
        if (!literalText.hasLocalizableText()) return

        val sink = expression.visibleTextSink() ?: return
        reportElement(
            expression,
            "Visible text literal \"${literalText.preview()}\" passed to ${sink.name} must come from a string resource. " +
                "Define it in values/strings.xml and values-zh-rCN/strings.xml, then use stringResource/getString.",
        )
    }

    private fun KtFile.isVisibleTextSource(): Boolean =
        isProductionSource() && sourcePathFragments.any { fragment -> fragment in path() }

    private fun KtStringTemplateExpression.visibleTextSink(): VisibleTextSink? {
        val argument = nearestValueArgument() ?: return null
        val call = argument.enclosingCall() ?: return null
        val argumentIndex = call.valueArguments.indexOf(argument)
        val argumentName = argument.getArgumentName()?.asName?.identifier
        val calleeName = call.calleeExpression?.text?.substringAfterLast('.') ?: return null

        if (calleeName in textCallees && (argumentName == null || argumentName == "text") && argumentIndex <= 0) {
            return VisibleTextSink(calleeName)
        }
        if (argumentName in contentDescriptionArgumentNames) {
            return VisibleTextSink("$calleeName.$argumentName")
        }
        if (calleeName == "makeText" && call.receiverSimpleName() == "Toast" && argumentIndex == TOAST_TEXT_ARGUMENT_INDEX) {
            return VisibleTextSink("Toast.makeText")
        }
        if (calleeName == "showSnackbar" && (argumentName == null || argumentName == "message") && argumentIndex == 0) {
            return VisibleTextSink("showSnackbar")
        }
        if (calleeName == "newPlainText" && call.receiverSimpleName() == "ClipData" && argumentIndex == 0) {
            return VisibleTextSink("ClipData.newPlainText")
        }
        if (calleeName in androidTextSetterCallees && argumentIndex == 0) {
            return VisibleTextSink(calleeName)
        }

        return null
    }

    private fun KtStringTemplateExpression.nearestValueArgument(): KtValueArgument? {
        var current: PsiElement? = parent
        while (current != null && current !is KtFile) {
            if (current is KtValueArgument) return current
            current = current.parent
        }
        return null
    }

    private fun KtValueArgument.enclosingCall(): KtCallExpression? {
        var current: PsiElement? = parent
        while (current != null && current !is KtFile) {
            if (current is KtCallExpression) return current
            current = current.parent
        }
        return null
    }

    private fun KtCallExpression.receiverSimpleName(): String? =
        ((parent as? KtDotQualifiedExpression)?.receiverExpression?.text)
            ?.substringAfterLast('.')

    private fun KtStringTemplateExpression.localizableLiteralText(): String =
        entries
            .filterNot { entry -> entry is KtStringTemplateEntryWithExpression }
            .joinToString(separator = "") { entry -> entry.text }
            .replace(escapedUnicodePattern, "")
            .replace(escapedWhitespacePattern, " ")
            .replace(formatSpecifierPattern, "")

    private fun String.hasLocalizableText(): Boolean = localizableLetterPattern.containsMatchIn(this)

    private fun String.preview(): String =
        trim()
            .replace(Regex("""\s+"""), " ")
            .let { value ->
                if (value.length <= MAX_PREVIEW_LENGTH) {
                    value
                } else {
                    value.take(MAX_PREVIEW_LENGTH) + "..."
                }
            }

    private data class VisibleTextSink(
        val name: String,
    )

    private companion object {
        const val TOAST_TEXT_ARGUMENT_INDEX = 1
        const val MAX_PREVIEW_LENGTH = 48
        val escapedUnicodePattern = Regex("""\\u[0-9A-Fa-f]{4}""")
        val escapedWhitespacePattern = Regex("""\\[nrt]""")
        val formatSpecifierPattern = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[tT]?[A-Za-z]""")
        val localizableLetterPattern = Regex("""\p{L}""")
    }
}
