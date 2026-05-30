package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

internal class NoRelaxedMockkRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Tests must not use relaxed MockK for stateful collaborators. Use Fake* or a test harness.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isTestFile()) return
        if (expression.calleeExpression?.text != "mockk") return
        if (!expression.valueArguments.any { argument -> argument.text.contains("relaxed = true") }) return

        val typeText = expression.getMockedTypeText()
        if (typeText != null && typeText.isAllowedRelaxedMockType()) return

        reportElement(
            expression,
            "Avoid mockk(relaxed = true). Model behavior with a Fake* or test harness; relaxed mocks hide missing BDD outcomes.",
        )
    }

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        val file = annotationEntry.containingKtFile
        if (!file.isTestFile()) return
        val shortName = annotationEntry.shortName?.asString() ?: return
        val isRelaxedMock =
            shortName == "RelaxedMockK" ||
                (shortName == "MockK" && annotationEntry.valueArgumentList?.text.orEmpty().contains("relaxed = true"))
        if (!isRelaxedMock) return

        val property = annotationEntry.parent?.parent as? KtProperty
        val typeText = property?.typeReference?.text?.trim()
        if (typeText != null && typeText.isAllowedRelaxedMockType()) return

        report(
            dev.detekt.api.Finding(
                dev.detekt.api.Entity.from(annotationEntry),
                "Avoid relaxed MockK annotations. Model behavior with a Fake* or test harness.",
            ),
        )
    }

    private fun String.isAllowedRelaxedMockType(): Boolean {
        val simpleName = substringAfterLast('.').trimEnd('?').substringBefore('<')
        return simpleName in ALLOWED_RELAXED_SIMPLE_TYPES ||
            ALLOWED_RELAXED_PREFIXES.any { prefix -> startsWith(prefix) }
    }

    private companion object {
        val ALLOWED_RELAXED_SIMPLE_TYPES =
            setOf(
                "Context",
                "ContentResolver",
                "Uri",
                "WorkerParameters",
                "Application",
                "Intent",
            )
        val ALLOWED_RELAXED_PREFIXES =
            setOf(
                "android.",
                "androidx.",
            )
    }
}

internal class NoThreadSleepInTestsRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Tests must use deterministic schedulers, fake clocks, or coroutine test utilities instead of Thread.sleep.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isTestFile()) return
        if (expression.calleeExpression?.text != "sleep") return
        val receiver = (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text ?: return
        if (receiver.substringAfterLast('.') != "Thread") return

        reportElement(
            expression,
            "Thread.sleep in tests is nondeterministic. Use a fake clock, scheduler hook, runTest, or advanceUntilIdle.",
        )
    }
}

internal class NoInteractionOnlyTestRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Tests that verify collaborator calls must also assert an observable behavior outcome.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isTestFile()) return
        val callee = expression.calleeExpression?.text ?: return
        if (callee != "test") return

        val bodyText = expression.text
        if (!VERIFY_REGEX.containsMatchIn(bodyText)) return
        if (ASSERTION_TOKENS.any { token -> token in bodyText }) return

        reportElement(
            expression,
            "Interaction-only test detected. Add an observable assertion, or replace the collaborator with a Fake* exposing recorded state.",
        )
    }

    private companion object {
        val VERIFY_REGEX = Regex("""\b(?:coVerify|verify)(?:Order|Sequence|All)?\b""")
        val ASSERTION_TOKENS =
            listOf(
                " shouldBe ",
                " shouldNotBe ",
                "shouldBeTrue",
                "shouldBeFalse",
                "shouldThrow",
                "shouldHaveSize",
                "shouldContain",
                "shouldBeNull",
                "shouldNotBeNull",
                "shouldBeInstanceOf",
                "assertSoftly",
                "awaitItem() should",
            )
    }
}

internal class NoFlowFirstForStateSequenceRule(
    config: Config,
) : LomoBaseRule(
    config,
    "ViewModel state-flow tests should use Turbine or explicit state assertions instead of first() for user-visible state sequences.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val file = expression.containingKtFile
        if (!file.isTestFile()) return
        if (!file.path().substringAfterLast('/').contains("ViewModelTest")) return
        if (expression.calleeExpression?.text != "first") return

        val receiverText = (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text.orEmpty()
        if (STATE_FLOW_RECEIVER_TOKENS.none { token -> token in receiverText }) return

        reportElement(
            expression,
            "ViewModel state flow uses first(). Use Turbine or assert uiState.value after advancing the dispatcher.",
        )
    }

    private companion object {
        val STATE_FLOW_RECEIVER_TOKENS = listOf("uiState", "state", "event", "events")
    }
}

internal class ExcessiveMockStubbingRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Too many mock stubbings (every/coEvery) detected. Model behavior with Fake* or test harness instead of mocking SUT dependencies.",
) {
    private var everyCount = 0

    override fun visitKtFile(file: KtFile) {
        everyCount = 0
        super.visitKtFile(file)
        val limit = config.valueOrDefault("maxEveryStubbingCount", 5)
        if (everyCount > limit) {
            reportFile(
                file,
                "Excessive mock stubbings ($everyCount > $limit). Model behavior with a Fake*, test harness, Turbine, or call-recording fake instead of stubbing multiple dependencies."
            )
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.containingKtFile.isTestFile()) return
        val calleeText = expression.calleeExpression?.text
        if (calleeText == "every" || calleeText == "coEvery") {
            everyCount++
        }
    }
}

internal class NoSourceStringBehaviorTestRule(
    config: Config,
) : LomoBaseRule(
    config,
    "Non-architecture tests must not assert on Kotlin source-string tokens. Extract the production logic into a testable unit.",
) {
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.isTestFile()) return
        
        val path = file.path()
        val isArchitecture = path.contains("/architecture/")
        val hasBoundaryMarker = file.text.lineSequence().take(30).any { it.contains("// architectural-boundary-check") }
        if (isArchitecture || hasBoundaryMarker) return

        // Check if it contains File and readText() and string assertions
        val hasFileReference = file.text.contains("File(") || file.text.contains("java.io.File")
        val hasReadText = file.text.contains(".readText()")
        val hasAssertion = file.text.contains("shouldContain") || file.text.contains(".contains(") || file.text.contains("assertTrue")
        
        if (hasFileReference && hasReadText && hasAssertion) {
            reportFile(
                file,
                "Source-string assertion test forbidden. Extract the production logic into a testable unit and assert observable outcomes. To allow an architecture-boundary file, add the '// architectural-boundary-check' marker."
            )
        }
    }
}
