package com.lomo.data.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: workspace document command result boundary.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: a verified document command result must carry the affected memo facts parsed by
 *   Rust against the same path and identity, and the affected fingerprint must equal the verified
 *   result fingerprint before the data layer may commit projection state.
 *
 * Scenarios:
 * - Given a result whose affected memo matches the mutation target and result fingerprint, when it
 *   crosses the boundary, then the facts are returned.
 * - Given a result whose affected memo targets another identity or carries a different fingerprint,
 *   when it crosses the boundary, then validation rejects it before projection state is committed.
 * - Given a result without affected memo facts, when it crosses the boundary, then validation
 *   rejects it instead of fabricating facts.
 *
 * Observable outcomes:
 * - The returned affected memo facts and thrown target-validation failures.
 *
 * TDD proof:
 * - RED before the fix because unverified or mismatched affected facts could reach the projection.
 *
 * Excludes:
 * - Rust document ordering, provider I/O, and SQLite projection internals.
 * Test Change Justification:
 * - Reason category: product/domain contract changed.
 * - Old behavior/assertion being replaced: the retired trash-command result boundary assertions.
 * - Why old assertion is no longer correct: the scan/trash surface was removed; the surviving contract is requireAffectedMemo document-command binding.
 * - Coverage preserved by: boundary verification cases re-expressed on the surviving document-command path.
 * - Why this is not fitting the test to the implementation: it pins the remaining live contract after dead-surface deletion.
 */
class SafMemoCommandBoundaryTest : FunSpec({
    test("given a verified result when affected facts match the target then they are returned") {
        val affected = memoFacts(path = "2026-08-17.md", identity = "memo-1", fingerprint = "after")
        val result =
            WorkspaceNativeCommandResultSnapshot(
                path = "2026-08-17.md",
                resultFingerprint = "after",
                bytesWritten = 12uL,
                affectedMemo = affected,
            )

        result.requireAffectedMemo(path = "2026-08-17.md", identity = "memo-1") shouldBe affected
    }

    test("given a result for another identity when boundary validates then it is rejected") {
        val result =
            WorkspaceNativeCommandResultSnapshot(
                path = "2026-08-17.md",
                resultFingerprint = "same",
                bytesWritten = 12uL,
                affectedMemo = memoFacts(path = "2026-08-17.md", identity = "memo-2", fingerprint = "same"),
            )

        val failure =
            shouldThrow<IllegalArgumentException> {
                result.requireAffectedMemo(path = "2026-08-17.md", identity = "memo-1")
            }

        failure.message shouldBe "Affected memo identity does not match the mutation target"
    }

    test("given an affected fingerprint drift when boundary validates then it is rejected") {
        val result =
            WorkspaceNativeCommandResultSnapshot(
                path = "2026-08-17.md",
                resultFingerprint = "after",
                bytesWritten = 12uL,
                affectedMemo = memoFacts(path = "2026-08-17.md", identity = "memo-1", fingerprint = "before"),
            )

        val failure =
            shouldThrow<IllegalArgumentException> {
                result.requireAffectedMemo(path = "2026-08-17.md", identity = "memo-1")
            }

        failure.message shouldBe "Affected memo fingerprint does not match the verified document result"
    }

    test("given a result without affected facts when boundary validates then it is rejected") {
        val result =
            WorkspaceNativeCommandResultSnapshot(
                path = "2026-08-17.md",
                resultFingerprint = "after",
                bytesWritten = 12uL,
                affectedMemo = null,
            )

        val failure =
            shouldThrow<IllegalArgumentException> {
                result.requireAffectedMemo(path = "2026-08-17.md", identity = "memo-1")
            }

        failure.message shouldBe "Completed document mutation did not publish Rust-parsed affected memo facts"
    }
})

private fun memoFacts(
    path: String,
    identity: String,
    fingerprint: String,
): WorkspaceDocumentMemoFactsSnapshot =
    WorkspaceDocumentMemoFactsSnapshot(
        path = path,
        identity = identity,
        timePart = "09:30:00",
        fingerprint = fingerprint,
        tags = emptyList(),
        attachments = emptyList(),
        reminders = emptyList(),
        hasTodo = false,
        hasUrl = false,
    )
