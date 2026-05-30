/*
 * Behavior Contract:
 * - Unit under test: MemoContentAnalyzer
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: provide a single domain-owned memo content analysis contract for query/filter decisions.
 *
 * Scenarios:
 * - Given markdown todo syntax, when content is analyzed, then todo presence is reported.
 * - Given markdown image, wiki image, or audio markdown references, when content is analyzed, then attachment presence is reported.
 * - Given markdown image, wiki image, and audio markdown references, when content is analyzed, then canonical attachment targets are reported once in source order.
 * - Given http, geo, mailto, or raw email references, when content is analyzed, then URL/contact presence is reported.
 * - Given plain text, when content is analyzed, then no content flags are reported.
 *
 * Observable outcomes:
 * - MemoContentAnalysis boolean fields returned from a content string.
 *
 * TDD proof:
 * - Fails before implementation because MemoContentAnalyzer and MemoContentAnalysis do not exist.
 *
 * Excludes:
 * - repository SQL, UI filtering, markdown rendering, attachment loading.
 */
package com.lomo.domain.usecase

import com.lomo.domain.model.MemoContentAnalysis
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MemoContentAnalyzerTest : FunSpec({
    test("given markdown todo when content is analyzed then todo presence is reported") {
        MemoContentAnalyzer.analyze("- [ ] buy milk") shouldBe
            MemoContentAnalysis(hasTodo = true)
    }

    test("given markdown and wiki images when content is analyzed then attachment presence is reported") {
        MemoContentAnalyzer.analyze("![alt](images/a.png)\n![[camera.jpg]]") shouldBe
            MemoContentAnalysis(
                hasAttachment = true,
                imageUrls = listOf("images/a.png", "camera.jpg"),
            )
    }

    test("given audio markdown link when content is analyzed then attachment presence is reported") {
        MemoContentAnalyzer.analyze("voice memo [play](recordings/clip.MP3)") shouldBe
            MemoContentAnalysis(
                hasAttachment = true,
                audioUrls = listOf("recordings/clip.MP3"),
            )
    }

    test("given duplicate image and audio references when content is analyzed then canonical targets are source ordered") {
        MemoContentAnalyzer.analyze(
            """
            ![cover](images/a.png)
            ![[camera.jpg]]
            ![duplicate](images/a.png)
            [voice](recordings/clip.MP3)
            """.trimIndent(),
        ) shouldBe
            MemoContentAnalysis(
                hasAttachment = true,
                imageUrls = listOf("images/a.png", "camera.jpg"),
                audioUrls = listOf("recordings/clip.MP3"),
            )
    }

    test("given http geo and email references when content is analyzed then url presence is reported") {
        val samples =
            listOf(
                "see https://example.com/path?q=1",
                "meet at geo:-29.1645,141.5243?z=10",
                "send mailto:someone@example.com",
                "contact someone@example.com",
            )

        samples.map(MemoContentAnalyzer::analyze) shouldBe
            List(samples.size) { MemoContentAnalysis(hasUrl = true) }
    }

    test("given plain text when content is analyzed then no content flags are reported") {
        MemoContentAnalyzer.analyze("plain memo body") shouldBe MemoContentAnalysis.None
    }
})
