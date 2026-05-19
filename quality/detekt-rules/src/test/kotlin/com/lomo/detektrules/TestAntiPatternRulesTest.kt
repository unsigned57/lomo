// architectural-boundary-check
package com.lomo.detektrules

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import dev.detekt.api.Config
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/*
 * Behavior Contract:
 * - Unit under test: Lomo test anti-pattern Detekt rules
 * - Owning layer: quality
 * - Priority tier: P1
 * - Capability: reject BDD+TDD test shapes that hide behavior behind mocks or timing.
 *
 * Scenarios:
 * - Given a relaxed repository mock, when test-style Detekt runs, then it reports a fake-first violation.
 * - Given a test sleeps a thread, when test-style Detekt runs, then it reports deterministic coroutine/test-clock guidance.
 * - Given a test only verifies collaborator calls, when test-style Detekt runs, then it requires an observable assertion.
 * - Given a ViewModel state flow uses first(), when test-style Detekt runs, then it asks for Turbine or explicit state assertions.
 *
 * Observable outcomes:
 * - registered rule names, finding counts, and actionable failure messages.
 *
 * TDD proof:
 * - Fails before the fix because the new test anti-pattern rules are not registered.
 *
 * Excludes:
 * - full Gradle task integration and semantic type resolution.
 */
class TestAntiPatternRulesTest : FunSpec({
    test("registers test anti-pattern guard rules") {
        val rules = LomoTestStyleRuleSetProvider().instance().rules

        rules[RuleName("NoRelaxedMockk")].shouldNotBeNull()
        rules[RuleName("NoThreadSleepInTests")].shouldNotBeNull()
        rules[RuleName("NoInteractionOnlyTest")].shouldNotBeNull()
        rules[RuleName("NoFlowFirstForStateSequence")].shouldNotBeNull()
        rules[RuleName("ExcessiveMockStubbing")].shouldNotBeNull()
        rules[RuleName("NoSourceStringBehaviorTest")].shouldNotBeNull()
    }

    test("flags relaxed MockK for stateful collaborators") {
        val findings =
            rule("NoRelaxedMockk").findingsForTestSource(
                """
                package com.lomo.sample

                import io.mockk.mockk

                interface MemoRepository

                class MemoUseCaseTest {
                    private val repository: MemoRepository = mockk(relaxed = true)
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "relaxed"
    }

    test("allows relaxed MockK for Android framework seams") {
        val findings =
            rule("NoRelaxedMockk").findingsForTestSource(
                """
                package com.lomo.sample

                import android.content.Context
                import io.mockk.mockk

                class AndroidBoundaryTest {
                    private val context: Context = mockk(relaxed = true)
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("flags Thread.sleep in test source") {
        val findings =
            rule("NoThreadSleepInTests").findingsForTestSource(
                """
                package com.lomo.sample

                class SlowTest {
                    fun waits() {
                        Thread.sleep(25)
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "deterministic"
    }

    test("flags interaction-only tests") {
        val findings =
            rule("NoInteractionOnlyTest").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.core.spec.style.FunSpec
                import io.mockk.coVerify

                class SaveMemoTest : FunSpec({
                    test("saves memo") {
                        coVerify { repository.saveMemo("body") }
                    }
                })
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "observable"
    }

    test("allows interaction checks with an observable assertion") {
        val findings =
            rule("NoInteractionOnlyTest").findingsForTestSource(
                """
                package com.lomo.sample

                import io.kotest.core.spec.style.FunSpec
                import io.kotest.matchers.shouldBe
                import io.mockk.coVerify

                class SaveMemoTest : FunSpec({
                    test("saves memo") {
                        result shouldBe SaveResult.Success
                        coVerify { repository.saveMemo("body") }
                    }
                })
                """,
            )

        findings shouldBe emptyList()
    }

    test("flags ViewModel state flow first usage") {
        val findings =
            rule("NoFlowFirstForStateSequence").findingsForTestSource(
                relativePath = "src/test/java/com/lomo/app/feature/main/MainViewModelTest.kt",
                code =
                    """
                    package com.lomo.sample

                    import kotlinx.coroutines.flow.first

                    suspend fun assertState(viewModel: MainViewModel) {
                        viewModel.uiState.first()
                    }
                    """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Turbine"
    }

    test("flags excessive mock stubbings when limit exceeded") {
        val findings =
            rule("ExcessiveMockStubbing").findingsForTestSource(
                """
                package com.lomo.sample

                import io.mockk.every

                class TooManyStubs {
                    fun setup(mockRepo: java.util.List<String>) {
                        every { mockRepo[0] } returns "a"
                        every { mockRepo[1] } returns "b"
                        every { mockRepo[2] } returns "c"
                        every { mockRepo[3] } returns "d"
                        every { mockRepo[4] } returns "e"
                        every { mockRepo[5] } returns "f"
                    }
                }
                """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Excessive mock stubbings"
    }

    test("allows few mock stubbings within limit") {
        val findings =
            rule("ExcessiveMockStubbing").findingsForTestSource(
                """
                package com.lomo.sample

                import io.mockk.every

                class SafeStubs {
                    fun setup(mockRepo: java.util.List<String>) {
                        every { mockRepo[0] } returns "a"
                    }
                }
                """,
            )

        findings shouldBe emptyList()
    }

    test("flags non-architecture source-string behavior tests") {
        val findings =
            rule("NoSourceStringBehaviorTest").findingsForTestSource(
                relativePath = "src/test/java/com/lomo/sample/BusinessLogicTest.kt",
                code =
                    """
                    package com.lomo.sample

                    import java.io.File
                    import io.kotest.matchers.string.shouldContain

                    class BusinessLogicTest {
                        fun testSource() {
                            val content = File("src/main/java/com/lomo/sample/Policy.kt").readText()
                            content shouldContain "class Policy"
                        }
                    }
                    """,
            )

        findings.shouldHaveSize(1)
        findings.single().message shouldContain "Source-string assertion test forbidden"
    }

    test("allows source-string test if in architecture package") {
        val findings =
            rule("NoSourceStringBehaviorTest").findingsForTestSource(
                relativePath = "src/test/java/com/lomo/sample/architecture/DomainPurityTest.kt",
                code =
                    """
                    package com.lomo.sample.architecture

                    import java.io.File
                    import io.kotest.matchers.string.shouldContain

                    class DomainPurityTest {
                        fun testSource() {
                            val content = File("src/main/java/com/lomo/sample/Policy.kt").readText()
                            content shouldContain "class Policy"
                        }
                    }
                    """,
            )

        findings shouldBe emptyList()
    }

    test("allows source-string test if architectural-boundary-check marker is present") {
        val findings =
            rule("NoSourceStringBehaviorTest").findingsForTestSource(
                relativePath = "src/test/java/com/lomo/sample/BusinessLogicTest.kt",
                code =
                    """
                    // architectural-boundary-check
                    package com.lomo.sample

                    import java.io.File
                    import io.kotest.matchers.string.shouldContain

                    class BusinessLogicTest {
                        fun testSource() {
                            val content = File("src/main/java/com/lomo/sample/Policy.kt").readText()
                            content shouldContain "class Policy"
                        }
                    }
                    """,
            )

        findings shouldBe emptyList()
    }
})

private fun rule(
    name: String,
    config: Config = Config.empty,
): Rule =
    checkNotNull(LomoTestStyleRuleSetProvider().instance().rules[RuleName(name)]) {
        "Expected rule '$name' to be registered."
    }.invoke(config)

private fun Rule.findingsForTestSource(
    code: String,
    relativePath: String = "src/test/java/com/lomo/sample/FixtureTest.kt",
): List<Finding> {
    val tempDir = Files.createTempDirectory("lomo-detekt-rule-test")
    val file = tempDir.resolve(relativePath)
    file.parent.createDirectories()
    file.writeText(code.trimIndent())
    return lint(compileForTest(file))
}
