/*
 * Behavior Contract:
 * - Unit under test: EngineReadiness.requireWritable / isWritable.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: only Ready is writable; all other engine states and write freeze fail closed.
 *
 * Scenarios:
 * - Given Ready, when requireWritable runs, then it succeeds and isWritable is true.
 * - Given AwaitingWorkspaceSelection/Opening/ReadOnlyRecovery/ShuttingDown, when requireWritable
 *   runs, then IllegalStateException is raised and isWritable is false.
 * - Given Ready with write freeze, when requireWritable runs, then it fails closed.
 *
 * Observable outcomes: exception messages and boolean writability.
 * TDD proof: fails before requireWritable exists.
 * Excludes: Android recovery UI and Rust engine internals.
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
                    category = EngineReadiness.FailureCategory.CORRUPTION,
                    code = "journal_corrupt",
                    retryDisposition = EngineReadiness.RetryDisposition.NEVER,
                    diagnostic = "checksum mismatch",
                ).requireWritable()
            }.message.shouldContain("journal_corrupt")
            EngineReadiness
                .ReadOnlyRecovery(
                    category = EngineReadiness.FailureCategory.CORRUPTION,
                    code = "journal_corrupt",
                    retryDisposition = EngineReadiness.RetryDisposition.NEVER,
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
    }
}
