// adversarial-audit: the generation watermark inside StoreInvalidationBus has exactly one
// authority — the session's activation counter. A projection-only re-anchor (archive import)
// must never consume a session generation, and a genuinely stale generation must still throw.
package com.lomo.data.repository

/*
 * Original breach hypothesis (now fixed): ManagedEngineSession mints `authority.generation`
 * from its private `activationGeneration` counter and passes it to
 * StoreInvalidationBus.reanchor, which requires `generation > lastGeneration`. The archived
 * implementation of reanchorProjection did `lastGeneration += 1` without informing the session
 * counter, so the next activateWorkspace produced generation == lastGeneration and the
 * require() inside reanchor threw — after the new adapter was already installed but before the
 * mount was published.
 *
 * The fixed contract under lock: reanchorProjection resets only the revision/sequence
 * watermarks; the session counter stays the single generation authority, and stale
 * generations remain rejected.
 *
 * Behavior Contract:
 * - Unit under test: StoreInvalidationBus reanchor/generation watermark ownership.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: the session activation counter is the sole generation authority; a
 *   projection-only re-anchor never consumes a generation and a replayed generation throws.
 *
 * Scenarios:
 * - Given an archive-import re-anchor, when the next workspace activation runs, then its fresh
 *   generation is accepted instead of colliding.
 * - Given a replayed/stale generation, when reanchor is called, then it throws
 *   IllegalArgumentException.
 * - Given a non-positive commit receipt, when surfaced, then it is a structured ProtocolFailure.
 *
 * Observable outcomes: reanchor accept/reject decisions and typed failure surfaces.
 *
 * TDD proof:
 * - The collision arm failed RED while reanchorProjection incremented the bus watermark itself;
 *   GREEN once only revision/sequence watermarks reset.
 *
 * Excludes:
 * - Engine session lifecycle and archive-format handling (covered by session contracts).
 *
 * Test Change Justification:
 * - Reason category: behavior lock re-pinned after the breach fix (single-authority generation).
 * - Old behavior/assertion being replaced: the first probe asserted the collision is thrown.
 * - Why old assertion is no longer correct: the collision was the defect; asserting it would
 *   permanently pin the bug.
 * - Coverage preserved by: the same fixture now asserts the post-import activation reanchor is
 *   accepted AND a replayed generation still throws IllegalArgumentException.
 * - Why this is not fitting the test to the implementation: the assertions encode the
 *   single-authority contract, independent of how the bus implements it.
 */

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReanchorCollisionContractTest : FunSpec({
    test("archive import then next workspace activation reanchor does not collide; stale generation still rejected") {
        val bus = StoreInvalidationBus()

        // First activation: session mints generation 1.
        bus.reanchor(generation = 1, highWaterRevision = 5)
        bus.publications.value.coreRevision shouldBe 5

        // Archive import replaces the projection without an activation — it must not consume
        // a generation the session counter will still issue.
        bus.reanchorProjection(highWaterRevision = 2)

        // Second activation: session mints generation 2 — a new generation, not an equality
        // collision against the projection re-anchor.
        bus.reanchor(generation = 2, highWaterRevision = 9)
        bus.publications.value.coreRevision shouldBe 9

        // A replayed or regressed generation is still a protocol violation.
        shouldThrow<IllegalArgumentException> {
            bus.reanchor(generation = 2, highWaterRevision = 10)
        }
        shouldThrow<IllegalArgumentException> {
            bus.reanchor(generation = 1, highWaterRevision = 11)
        }
    }

    test("expected contract: a post-import activation is a new generation, not a stale one") {
        val bus = StoreInvalidationBus()
        bus.reanchor(generation = 1, highWaterRevision = 5)
        bus.reanchorProjection(highWaterRevision = 2)

        shouldNotThrowAny {
            // The session-owned counter produces 2 here; the projection incarnation bump must
            // not consume a generation the session will legitimately issue.
            bus.reanchor(generation = 2, highWaterRevision = 9)
        }
        bus.publications.value.coreRevision shouldBe 9
    }

    test("non-positive commit receipt still surfaces structured ProtocolFailure") {
        val bus = StoreInvalidationBus()
        bus.reanchor(generation = 1, highWaterRevision = 5)
        bus.reanchorProjection(highWaterRevision = 2)

        // Even if activation gen bookkeeping were fixed, receipts must stay classified.
        val failure =
            shouldThrow<com.lomo.domain.model.EngineCommandFailureException> {
                bus.publish(
                    com.lomo.data.engine.store.StoreMemoCommit(
                        operationId = "op-0",
                        memoId = "memo-1",
                        coreRevision = 0,
                        eventSequence = 0,
                        contentRevision = 0,
                        fileFingerprint = "fp",
                        scopes = emptyList(),
                        idempotentReplay = false,
                    ),
                )
            }
        failure.failure.code shouldBe StoreInvalidationBus.PROTOCOL_FAILURE_CODE
    }
})
