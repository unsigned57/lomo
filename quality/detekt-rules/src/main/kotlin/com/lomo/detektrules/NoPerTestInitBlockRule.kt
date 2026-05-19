package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassInitializer

internal class NoPerTestInitBlockRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Kotest specs must use a single init { ... } block (or constructor-block form); one init per test is a JUnit4-migration anti-pattern.",
) {
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        val file = klass.containingKtFile
        val path = file.getViewProvider().getVirtualFile().getPath().replace('\\', '/')
        if (!path.contains("/src/test/") && !path.contains("/src/androidTest/")) return

        if (!klass.looksLikeKotestSpec()) return

        val initializers = klass.body?.declarations?.filterIsInstance<KtClassInitializer>().orEmpty()
        if (initializers.size <= 1) return

        val allSingleTestCall = initializers.all { it.holdsSingleSpecBlockCall() }
        if (!allSingleTestCall) return

        reportDeclaration(
            klass,
            "Kotest spec uses one init block per test; collapse into a single init { ... } block. " +
                "This per-test init pattern is a JUnit4-migration anti-pattern.",
        )
    }

    private fun KtClass.looksLikeKotestSpec(): Boolean {
        val superTypeText = superTypeListEntries.joinToString(" ") { it.text }
        return SPEC_NAME_REGEX.containsMatchIn(superTypeText)
    }

    private fun KtClassInitializer.holdsSingleSpecBlockCall(): Boolean {
        val block = body ?: return false
        val statementsText = block.text.removePrefix("{").removeSuffix("}")
        val statements =
            statementsText
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("//") }
                .toList()

        val callExpressions =
            block.children
                .mapNotNull { child ->
                    val expr = (child as? org.jetbrains.kotlin.psi.KtExpression) ?: return@mapNotNull null
                    expr as? KtCallExpression ?: expr.children.firstOrNull() as? KtCallExpression
                }
                .filter { it.calleeExpression?.text in SPEC_BLOCK_NAMES }

        if (callExpressions.size != 1) return false
        return statements.size <= 6
    }

    private companion object {
        val SPEC_NAME_REGEX =
            Regex(
                "\\b(FunSpec|BehaviorSpec|DescribeSpec|WordSpec|ShouldSpec|StringSpec|FeatureSpec|ExpectSpec|FreeSpec|" +
                    "AnnotationSpec|DomainFunSpec|DataFunSpec|AppFunSpec|UiComponentsFunSpec)\\b",
            )

        val SPEC_BLOCK_NAMES =
            setOf(
                "test",
                "xtest",
                "given",
                "Given",
                "xgiven",
                "feature",
                "Feature",
                "describe",
                "xdescribe",
                "should",
                "context",
                "expect",
            )
    }
}
