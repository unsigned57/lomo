package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: MemoEditDraft
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: bind an editor draft to the exact memo revision/fingerprint it was created from.
 *
 * Scenarios:
 * - Given a draft and the unchanged memo baseline, when admission is checked, then it is accepted.
 * - Given a changed revision or fingerprint, when admission is checked, then the draft is rejected.
 * - Given an unbounded or incomplete draft, when it is constructed, then the invalid state is
 *   rejected at the domain boundary.
 *
 * Observable outcomes:
 * - baseline-match decision and validation exceptions.
 *
 * TDD proof:
 * - RED before the fix because no typed durable edit-session baseline existed.
 *
 * Excludes:
 * - DataStore serialization and Compose lifecycle wiring.
 */
class MemoEditDraftTest : DomainFunSpec() {
    init {
        test("given an unchanged memo baseline when draft admission is checked then it is accepted") {
            val draft = MemoEditDraft("memo-1", 4L, "fp-4", "edited body")
            draft.matchesBaseline("memo-1", 4L, "fp-4") shouldBe true
        }

        test("given a changed revision or fingerprint when draft admission is checked then it is rejected") {
            val draft = MemoEditDraft("memo-1", 4L, "fp-4", "edited body")
            draft.matchesBaseline("memo-1", 5L, "fp-4") shouldBe false
            draft.matchesBaseline("memo-1", 4L, "fp-5") shouldBe false
            draft.matchesBaseline("other", 4L, "fp-4") shouldBe false
        }

        test("given incomplete or oversized facts when a draft is constructed then validation fails") {
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft("", 1L, "fp", "body")
            }
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft("memo", 0L, "fp", "body")
            }
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft("memo", 1L, "", "body")
            }
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft("memo", 1L, "fp", "x".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1))
            }
        }
    }
}
