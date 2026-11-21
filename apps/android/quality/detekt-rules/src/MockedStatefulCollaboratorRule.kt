package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression

internal class MockedStatefulCollaboratorRule(config: Config) :
    LomoBaseRule(
        config,
        "MockK is forbidden for stateful collaborators (Dao / Repository / DataSource). Prefer hand-written Fake* under testing/fakes/ in the owning module.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.containingKtFile.isTestFile()) return
        if (expression.calleeExpression?.text != "mockk") return

        val typeText = expression.getMockedTypeText()
        if (typeText == null) return

        val simpleName = typeText.substringAfterLast('.').trimEnd('?').takeWhile { it != '<' }
        if (BANNED_SUFFIXES.none { simpleName.endsWith(it) }) return

        reportElement(
            expression,
            "Stateful collaborator '$simpleName' must be a hand-written Fake*, not a mockk(). " +
                "See quality/testing/kotlin-test-style.md and testing/fakes/ under the owning module.",
        )
    }

    private companion object {
        val BANNED_SUFFIXES = listOf("Dao", "Repository", "DataSource")
    }
}
