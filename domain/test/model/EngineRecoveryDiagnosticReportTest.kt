/*
 * Behavior Contract:
 * - Unit under test: EngineReadiness.ReadOnlyRecovery.toDiagnosticReport.
 * - Owning layer: domain.
 * - Priority tier: P0.
 * - Capability: export a bounded, user-shareable recovery report without copying untrusted
 *   diagnostics, workspace paths, memo bodies, remote secrets, or capability tokens.
 *
 * Scenarios:
 * - Given an engine diagnostic containing a remote secret, memo body and capability token, when a
 *   recovery report is built, then only the typed category/code/retry and coarse workspace kind
 *   are exported.
 * - Given a malformed or line-breaking error code, when a report is built, then export fails
 *   closed instead of emitting an ambiguous diagnostic record.
 *
 * Observable outcomes: report bytes and validation failure.
 * TDD proof: RED on 2026-08-01 because no bounded recovery diagnostic report boundary existed.
 * Excludes: Android document picker and native journal parsing.
 */

package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class EngineRecoveryDiagnosticReportTest : DomainFunSpec() {
    init {
        test("given sensitive native diagnostic when report is built then only typed facts are exported") {
            val recovery =
                EngineReadiness.ReadOnlyRecovery(
                    category = EngineReadiness.FailureCategory.CORRUPTION,
                    code = "journal_checksum_mismatch",
                    retryDisposition = EngineReadiness.RetryDisposition.AFTER_USER_ACTION,
                    diagnostic =
                        "secret=remote-password body=private memo token=capability-123 " +
                            "path=/storage/emulated/0/Notes",
                )

            val report = recovery.toDiagnosticReport(RecoveryWorkspaceKind.SAF)

            report.schemaVersion shouldBe 1
            report.fileName shouldBe "lomo-recovery-diagnostic-v1.txt"
            report.content shouldContain "category=corruption"
            report.content shouldContain "code=journal_checksum_mismatch"
            report.content shouldContain "retry=after_user_action"
            report.content shouldContain "workspace_kind=saf"
            report.content shouldNotContain "remote-password"
            report.content shouldNotContain "private memo"
            report.content shouldNotContain "capability-123"
            report.content shouldNotContain "/storage/emulated"
            report.content.encodeToByteArray().size shouldBeLessThanOrEqual 4096
        }

        test("given an ambiguous error code when report is built then export fails closed") {
            val recovery =
                EngineReadiness.ReadOnlyRecovery(
                    category = EngineReadiness.FailureCategory.INTERNAL,
                    code = "bad\nworkspace_path=/secret",
                    retryDisposition = EngineReadiness.RetryDisposition.NEVER,
                    diagnostic = "not exported",
                )

            shouldThrow<IllegalArgumentException> {
                recovery.toDiagnosticReport(RecoveryWorkspaceKind.DIRECT)
            }
        }
    }
}
