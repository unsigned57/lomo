package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

internal class MockedStatefulCollaboratorRule(
    config: Config,
) : LomoBaseRule(
    config,
    "MockK is forbidden for stateful collaborators (Dao / Repository / DataSource). Prefer hand-written Fake* under app/test/.../testing/fakes/.",
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
                "See quality/testing/ai-kotlin-test-style.md (Prefer Fake* Collaborators) and app/test/testing/fakes/.",
        )
    }

    private companion object {
        val BANNED_SUFFIXES = listOf("Dao", "Repository", "DataSource")
    }
}
