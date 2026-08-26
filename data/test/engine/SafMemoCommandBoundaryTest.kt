package com.lomo.data.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: workspace trash command result boundary.
 * - Owning layer: data.
 * - Priority tier: P0.
 * - Capability: preserve the distinct pre-mutation memo snapshot and post-mutation document
 *   fingerprint published by a verified trash command.
 *
 * Scenarios:
 * - Given permanent delete removes a memo from its source document, when the verified result crosses
 *   the data boundary, then the affected pre-image may retain the source fingerprint while the
 *   command result carries the new document fingerprint.
 * - Given a trash result targets another path or memo identity, when it crosses the boundary, then
 *   validation rejects it before projection state can be committed.
 *
 * Observable outcomes:
 * - The returned affected memo facts and thrown target-validation failures.
 *
 * TDD proof:
 * - RED before the fix because the boundary incorrectly requires the affected pre-image fingerprint
 *   to equal the permanent-delete post-image fingerprint.
 *
 * Excludes:
 * - Rust trash ordering, provider I/O, and SQLite projection internals.
 */
class SafMemoCommandBoundaryTest : FunSpec({
    test("given permanent delete pre-image when result is verified then distinct post-image fingerprint is accepted") {
        val affected = memoFacts(path = "2026-08-17.md", identity = "memo-1", fingerprint = "before")
        val result =
            WorkspaceNativeTrashCommandResultSnapshot(
                path = "2026-08-17.md",
                resultFingerprint = "after",
                affectedMemo = affected,
                trashedAtMs = null,
            )

        result.requireAffectedMemo(
            path = "2026-08-17.md",
            identity = "memo-1",
            expectedSourceFingerprint = "before",
        ) shouldBe affected
    }

    test("given trash result for another target when boundary validates then it is rejected") {
        val result =
            WorkspaceNativeTrashCommandResultSnapshot(
                path = "2026-08-17.md",
                resultFingerprint = "same",
                affectedMemo = memoFacts(path = "2026-08-17.md", identity = "memo-2", fingerprint = "same"),
                trashedAtMs = null,
            )

        val failure =
            kotlin.runCatching {
                result.requireAffectedMemo(
                    path = "2026-08-17.md",
                    identity = "memo-1",
                    expectedSourceFingerprint = "same",
                )
            }.exceptionOrNull()

        failure?.message shouldBe "Trash affected memo identity does not match the mutation target"
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
