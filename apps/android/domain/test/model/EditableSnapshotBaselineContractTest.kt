// adversarial-audit: the "Preview cannot become an edit baseline" guard lives only in
// EditableMemoSnapshot.fromFullSnapshot's runtime check; Memo is a public data class whose
// contentKind is a plain constructor property, and list previews DO carry contentRevision +
// fileFingerprint, so copy(contentKind = Full) produces a snapshot the gate cannot distinguish
// from a real full-body read
package com.lomo.domain.model

import com.lomo.domain.testing.DomainFunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

/*
 * Hypothesis under audit: the spec claims "Preview 不能通过 copy(contentKind = Full) 升格"
 * (a Preview must not be upgraded into an editable snapshot by copying it as Full). The guard
 * is `EditableMemoSnapshot.fromFullSnapshot` checking `contentKind == Full && !isPending` plus
 * non-null revision/fingerprint. Because StorePagingSource's `toDomainMemo` fills
 * `contentRevision`/`fileFingerprint` on Preview rows too, `preview.copy(contentKind = Full)`
 * satisfies every checked condition — the runtime gate cannot tell a forged Full from a real
 * one. The fix the spec implies (type-level separation) is absent.
 */

/*
 * Behavior Contract:
 * - Unit under test: EditableMemoSnapshot.fromFullSnapshot
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: a list preview can never be promoted into an editable baseline; an edit session
 *   binds only a complete full-body snapshot whose projection character count covers its body.
 *
 * Scenarios:
 * - Given a genuine Preview memo, when bound as an edit baseline, then it is rejected.
 * - Given a Preview forged via copy(contentKind = Full), when bound, then it is rejected because
 *   the projection character count cannot cover the truncated body it carries.
 *
 * Observable outcomes:
 * - IllegalArgumentException on non-full or count-mismatched snapshots; a forged Full never binds
 *   a real fingerprint to a truncated body.
 *
 * TDD proof:
 * - RED: the second scenario failed pre-fix — a forged Full carried revision/fingerprint past the
 *   gate. GREEN after projectedCharCount was bound to the snapshot body.
 *
 * Excludes:
 * - Editor session lifecycle, draft media leases and UI admission (covered by app-layer specs).
 */
class EditableSnapshotBaselineContractTest : DomainFunSpec() {
    private fun previewMemo(): Memo =
        Memo(
            id = "memo-1",
            timestamp = 1_700_000_000_000L,
            content = "truncated preview body…",
            rawContent = "truncated preview body…",
            dateKey = "2026_09_09",
            // List previews carry a real baseline pair; see StorePagingSource.toDomainMemo.
            contentRevision = 7L,
            fileFingerprint = "fp-real-file",
            contentKind = MemoContentKind.Preview,
        )

    init {
        test("a genuine Preview memo is rejected as an edit baseline") {
            shouldThrow<IllegalArgumentException> {
                EditableMemoSnapshot.fromFullSnapshot(previewMemo())
            }
        }

        test("copy(contentKind = Full) upgrades a Preview past the edit-baseline gate") {
            val forged = previewMemo().copy(contentKind = MemoContentKind.Full)

            // Required behavior: still rejected. Pre-fix the forged Full was accepted.
            val outcome = runCatching { EditableMemoSnapshot.fromFullSnapshot(forged) }
            if (outcome.isSuccess) {
                // A regression would bind the REAL file fingerprint to a truncated body:
                // a save built on it passes the Rust CAS baseline while writing truncated text.
                val snapshot = outcome.getOrThrow()
                snapshot.baseline.fileFingerprint shouldBe "fp-real-file"
                snapshot.memo.content shouldBe "truncated preview body…"
            }
            outcome.isSuccess shouldBe false
        }
    }
}
