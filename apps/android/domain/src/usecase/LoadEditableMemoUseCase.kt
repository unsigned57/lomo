package com.lomo.domain.usecase

import com.lomo.domain.model.EditableMemoSnapshot
import com.lomo.domain.repository.MainListQueryRepository

/**
 * Loads the authoritative full snapshot an edit command is allowed to bind to.
 *
 * Callers hand in whatever `Memo` identity a list row carried — frequently a bounded preview —
 * and this use case re-reads the document through the single-memo read path so the edit baseline
 * is built from the complete body plus its own CAS metadata, never from the row's claims.
 * A memo that no longer exists, or whose snapshot fails the completeness gate, yields null so the
 * caller can surface "cannot edit" instead of binding a forged baseline.
 */
open class LoadEditableMemoUseCase(
    private val mainListQueryRepository: MainListQueryRepository,
) {
    open suspend operator fun invoke(memoId: String): EditableMemoSnapshot? {
        val snapshot = mainListQueryRepository.getMemoById(memoId) ?: return null
        // A stale projection row can claim Full while failing the completeness gate; editing it
        // is impossible, so the caller sees "no editable snapshot" rather than a crash.
        return try {
            EditableMemoSnapshot.fromFullSnapshot(snapshot)
        } catch (ignored: IllegalArgumentException) {
            // behavior-contract: silent-result-ok: a row failing the completeness gate is the
            // "no editable snapshot" outcome, not a failure worth surfacing
            null
        }
    }
}
