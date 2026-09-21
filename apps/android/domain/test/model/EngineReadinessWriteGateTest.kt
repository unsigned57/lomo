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
 * - Given a verified mount at matching revision, when admitsProjectionReads is asked, then it is true.
 * - Given Ready without authority or an unavailable projection, when admitsProjectionReads is asked, then it is false.
 * - Given Revalidating at the last verified revision, when reads and writes are asked, then reads match and writes fail.
 *
 * Observable outcomes: exception messages and boolean writability.
 * TDD proof: fails before requireWritable exists and before query admission checks projection state.
 * Excludes: Android recovery UI and Rust engine internals.
 *
 * Test Change Justification:
 * - Reason category: projection freshness collapsed to Unavailable/Revalidating/Verified.
 * - Old behavior/assertion being replaced: Building/Failed/Refreshing/Stale read-admission cases,
 *   including Refreshing/Stale write admission.
 * - Why old assertion is no longer correct: those freshness variants had no production publisher
 *   and Revalidating must be read-only; first-projection unreadability is Unavailable.
 * - Coverage preserved by: Unavailable, Revalidating, Verified, and Ready-without-readable-projection
 *   admission remain asserted at the mount/freshness boundary.
 * - Why this is not fitting the test to the implementation: the new cases are the A02 admission law.
 */

package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EngineReadinessWriteGateTest : DomainFunSpec() {
    init {
        test("given Ready when requireWritable then succeeds") {
            val readiness = EngineReadiness.Ready
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
            val readiness = EngineReadiness.Ready
            shouldThrow<IllegalStateException> {
                readiness.requireWritable(writeFrozen = true)
            }.message.shouldContain("switch is in progress")
            readiness.isWritable(writeFrozen = true) shouldBe false
            readiness.isWritable(writeFrozen = false) shouldBe true
        }

        test("given projection freshness when reads are admitted then state and revision must match") {
            ProjectionFreshness.Unavailable.permitsReadsAt(3uL) shouldBe false
            ProjectionFreshness.Revalidating(3uL).permitsReadsAt(3uL) shouldBe true
            ProjectionFreshness.Revalidating(3uL).permitsReadsAt(2uL) shouldBe false
            ProjectionFreshness.Verified(3uL).permitsReadsAt(3uL) shouldBe true
            ProjectionFreshness.Verified(3uL).permitsReadsAt(2uL) shouldBe false
            ProjectionFreshness.Unavailable.permitsWrites() shouldBe false
            ProjectionFreshness.Revalidating(3uL).permitsWrites() shouldBe false
            ProjectionFreshness.Verified(3uL).permitsWrites() shouldBe true
        }

        test("given a verified ready mount when reads are admitted then authority is published") {
            val authority = WorkspaceAuthority(workspaceId = "ws", generation = 1, projectionRevision = 3uL)
            val mount =
                WorkspaceMount(
                    readiness = EngineReadiness.Ready,
                    location = StorageLocation("/vault"),
                    authority = authority,
                    freshness = ProjectionFreshness.Verified(3uL),
                )

            mount.admitsProjectionReads shouldBe true
            mount.admittedAuthority shouldBe authority
        }

        test("given ready without a readable projection when reads are admitted then authority is withheld") {
            val authority = WorkspaceAuthority(workspaceId = "ws", generation = 1, projectionRevision = 0uL)
            val unavailable =
                WorkspaceMount(
                    readiness = EngineReadiness.Ready,
                    location = StorageLocation("/vault"),
                    authority = authority,
                    freshness = ProjectionFreshness.Unavailable,
                )
            val revalidating =
                WorkspaceMount(
                    readiness = EngineReadiness.Ready,
                    location = StorageLocation("/vault"),
                    authority = authority.copy(projectionRevision = 3uL),
                    freshness = ProjectionFreshness.Revalidating(3uL),
                )
            val opening = WorkspaceMount.Opening

            unavailable.admitsProjectionReads shouldBe false
            unavailable.admittedAuthority shouldBe null
            revalidating.admitsProjectionReads shouldBe true
            revalidating.admittedAuthority shouldBe revalidating.authority
            opening.admitsProjectionReads shouldBe false
            opening.admittedAuthority shouldBe null
        }
    }
}
