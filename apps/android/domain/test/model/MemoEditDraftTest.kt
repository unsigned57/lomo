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
 *
 * Test Change Justification:
 * - Reason category: draft identity added to the durable edit-draft record.
 * - Old behavior/assertion being replaced: MemoEditDraft constructed without a DraftId; staged
 *   media leases had no draft owner to bind against.
 * - Why old assertion is no longer correct: a durable draft without identity cannot own staged
 *   media leases, so commit-time lease transfer and restart reconciliation have no anchor.
 * - Coverage preserved by: baseline admission and validation scenarios unchanged; constructor
 *   sites now pass the minted DraftId alongside revision and fingerprint.
 * - Why this is not fitting the test to the implementation: assertions still check admission and
 *   rejection outcomes, not the identifier's storage representation.
 */
class MemoEditDraftTest : DomainFunSpec() {
    init {
        test("given an unchanged memo baseline when draft admission is checked then it is accepted") {
            val draft = MemoEditDraft(DraftId("draft-1"), "memo-1", 4L, "fp-4", "edited body")
            draft.matchesBaseline("memo-1", 4L, "fp-4") shouldBe true
        }

        test("given a changed revision or fingerprint when draft admission is checked then it is rejected") {
            val draft = MemoEditDraft(DraftId("draft-1"), "memo-1", 4L, "fp-4", "edited body")
            draft.matchesBaseline("memo-1", 5L, "fp-4") shouldBe false
            draft.matchesBaseline("memo-1", 4L, "fp-5") shouldBe false
            draft.matchesBaseline("other", 4L, "fp-4") shouldBe false
        }

        test("given incomplete or oversized facts when a draft is constructed then validation fails") {
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft(DraftId("draft-1"), "", 1L, "fp", "body")
            }
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft(DraftId("draft-1"), "memo", 0L, "fp", "body")
            }
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft(DraftId("draft-1"), "memo", 1L, "", "body")
            }
            shouldThrow<IllegalArgumentException> {
                MemoEditDraft(DraftId("draft-1"), "memo", 1L, "fp", "x".repeat(MemoConstraints.MAX_MEMO_LENGTH + 1))
            }
        }
    }
}
