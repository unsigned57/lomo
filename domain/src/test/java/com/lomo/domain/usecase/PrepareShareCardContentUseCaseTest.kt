package com.lomo.domain.usecase

import com.lomo.domain.model.ShareCardTextInput
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe

/*
 * Test Contract:
 * - Unit under test: PrepareShareCardContentUseCase
 * - Behavior focus: share-card tag extraction preserves user-facing tags and removes inline tags from body text.
 * - Observable outcomes: extracted tag list and final body text content.
 * - Red phase: Fails before the fix when inline emoji tags are ignored and remain in the body text.
 * - Excludes: presentation-layer truncation, typography, and UI rendering details.
 */
class PrepareShareCardContentUseCaseTest : DomainFunSpec() {
    private val useCase = PrepareShareCardContentUseCase()
    init {
        test("invoke keeps long content without truncation") {
            val longContent = "x".repeat(4500)

            val result =
                useCase(
                    ShareCardTextInput(
                        content = longContent,
                        sourceTags = emptyList(),
                    ),
                )

            result.bodyText shouldBe longContent
            (result.bodyText.endsWith("...")) shouldBe false
        }
    }
    init {
        test("invoke keeps all source tags without display truncation") {
            val tags =
                (1..8).map { index ->
                    "#feature_${"x".repeat(20)}_$index"
                }

            val result =
                useCase(
                    ShareCardTextInput(
                        content = "memo body",
                        sourceTags = tags,
                    ),
                )

            result.tags shouldBe tags.map { it.removePrefix("#") }
        }
    }
    init {
        test("invoke keeps original spacing semantics in body text") {
            val result =
                useCase(
                    ShareCardTextInput(
                        content = "line1  line2\n\n\nline3 #topic",
                        sourceTags = emptyList(),
                    ),
                )

            result.bodyText shouldBe "line1  line2\n\n\nline3"
        }
    }
    init {
        test("invoke extracts inline emoji tags and removes them from body text") {
            val result =
                useCase(
                    ShareCardTextInput(
                        content = "计划 #😀工作 和 #🎉",
                        sourceTags = emptyList(),
                    ),
                )

            result.tags shouldBe listOf("😀工作", "🎉")
            result.bodyText shouldBe "计划 和"
        }
    }
}
