package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: Memo.displayTimestamp().
 * - Owning layer: domain
 * - Priority tier: P1
 * - Capability: format a memo's display timestamp verbatim from its file-native dateKey and
 *   timePart without reformatting through user display preferences.
 *
 * Scenarios:
 * - Given memos from a mixed-format workspace with varying dateKey and timePart shapes, when
 *   displayTimestamp is called, then dateKey and timePart are preserved verbatim.
 * - Given an undated memo with an empty or blank timePart, when displayTimestamp is called, then
 *   only dateKey is returned without trailing whitespace.
 *
 * Observable outcomes:
 * - Exact displayTimestamp() string output.
 *
 * TDD proof:
 * - Fails if Memo re-formats timestampMillis through a global date/time setting or drops timePart.
 *
 * Excludes:
 * - Rust workspace parsing and UI text wrapping.
 */
class MemoDisplayTimestampTest : DomainFunSpec() {
    init {
        test("displayTimestamp preserves mixed file-native dateKey and timePart formats verbatim") {
            val cases = listOf(
                Triple("2026_05_25", "09:30:00", "2026_05_25 09:30:00"),
                Triple("2026-05-25", "9:30", "2026-05-25 9:30"),
                Triple("2026/05/25", "23:59", "2026/05/25 23:59"),
                Triple("2026_05_25", "", "2026_05_25"),
                Triple("2026-05-25", "   ", "2026-05-25"),
            )

            cases.forEach { (dateKey, timePart, expected) ->
                withClue("dateKey=$dateKey timePart='$timePart'") {
                    Memo(
                        id = "memo-$dateKey",
                        timestamp = 1_779_694_200_000L,
                        content = "body",
                        rawContent = "body",
                        dateKey = dateKey,
                        timePart = timePart,
                    ).displayTimestamp() shouldBe expected
                }
            }
        }
    }
}
