/*
 * Behavior Contract:
 * - Unit under test: EngineReadiness write gate and ProjectionFreshness read admission.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: only Ready is writable; all other engine states and write freeze fail closed.
 *
 * Scenarios:
 * - Given Ready, when requireWritable runs, then it succeeds and isWritable is true.
 * - Given AwaitingWorkspaceSelection/Opening/ReadOnlyRecovery/ShuttingDown, when requireWritable
 *   runs, then IllegalStateException is raised and isWritable is false.
 * - Given Ready with write freeze, when requireWritable runs, then it fails closed.
 * - Given projection freshness, when a query revision is admitted, then only readable states with
 *   the exact published revision pass.
 *
 * Observable outcomes: exception messages and boolean writability.
 * TDD proof: fails before requireWritable exists and before query admission checks projection state.
 * Excludes: Android recovery UI and Rust engine internals.
 *
 * Test Change Justification:
 * - Reason category: domain error model unification and projection admission locking.
 * - Old behavior/assertion being replaced: FailureCategory and RetryDisposition nested enum assertions.
 * - Why old assertion is no longer correct: failure enums moved to top-level domain model EngineFailure.kt.
 * - Coverage preserved by: all write gate and read admission scenarios remain fully tested.
 * - Why this is not fitting the test to the implementation: verifies domain contract invariants for write safety.
 */

package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EngineReadinessWriteGateTest : DomainFunSpec() {
    init {
        test("given Ready when requireWritable then succeeds") {
            val readiness = EngineReadiness.Ready(coreRevision = 1uL, eventSequence = 2uL)
            readiness.requireWritable()
            readiness.isWritable() shouldBe true
        }

        test("given non-Ready states when requireWritable then fails closed") {
            shouldThrow<IllegalStateException> {
                EngineReadiness.AwaitingWorkspaceSelection.requireWritable()
            }.message.shouldContain("awaiting workspace selection")
            EngineReadiness.AwaitingWorkspaceSelection.isWritable() shouldBe false

            shouldThrow<IllegalStateException> {
                EngineReadiness.Opening.requireWritable()
            }.message.shouldContain("opening")
            EngineReadiness.Opening.isWritable() shouldBe false

            shouldThrow<IllegalStateException> {
                EngineReadiness.ReadOnlyRecovery(
                    category = EngineFailureCategory.CORRUPTION,
                    code = "journal_corrupt",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    diagnostic = "checksum mismatch",
                ).requireWritable()
            }.message.shouldContain("journal_corrupt")
            EngineReadiness
                .ReadOnlyRecovery(
                    category = EngineFailureCategory.CORRUPTION,
                    code = "journal_corrupt",
                    retryDisposition = EngineRetryDisposition.NEVER,
                    diagnostic = "checksum mismatch",
                ).isWritable() shouldBe false

            shouldThrow<IllegalStateException> {
                EngineReadiness.ShuttingDown.requireWritable()
            }.message.shouldContain("shutting down")
            EngineReadiness.ShuttingDown.isWritable() shouldBe false
        }

        test("given Ready when write freeze is active then requireWritable fails closed") {
            val readiness = EngineReadiness.Ready(coreRevision = 1uL, eventSequence = 2uL)
            shouldThrow<IllegalStateException> {
                readiness.requireWritable(writeFrozen = true)
            }.message.shouldContain("switch is in progress")
            readiness.isWritable(writeFrozen = true) shouldBe false
            readiness.isWritable(writeFrozen = false) shouldBe true
        }

        test("given projection freshness when reads are admitted then state and revision must match") {
            ProjectionFreshness.Unavailable.permitsReadsAt(3uL) shouldBe false
            ProjectionFreshness.Building(0uL).permitsReadsAt(0uL) shouldBe false
            ProjectionFreshness.Failed(0uL, "scan_failed").permitsReadsAt(0uL) shouldBe false
            ProjectionFreshness.Refreshing(3uL).permitsReadsAt(3uL) shouldBe true
            ProjectionFreshness.Stale(3uL, "scan_failed").permitsReadsAt(3uL) shouldBe true
            ProjectionFreshness.Verified(3uL).permitsReadsAt(3uL) shouldBe true
            ProjectionFreshness.Verified(3uL).permitsReadsAt(2uL) shouldBe false
        }
    }
}
