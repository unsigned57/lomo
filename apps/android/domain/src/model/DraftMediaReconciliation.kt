package com.lomo.domain.model

/**
 * One durable staged-media claim owned by a draft, as witnessed by the Rust stage ledger.
 *
 * [stagedBytesPresent] distinguishes a live stage file from a ledger row whose bytes vanished
 * (host crash mid-write, external cleanup). A missing row can never be promoted: surfacing it
 * early is what turns an opaque commit rejection into a recoverable draft failure.
 */
data class DraftStagedMediaRef(
    val artifactId: String,
    val relativePath: String,
    val stagedBytesPresent: Boolean,
)

/**
 * Restart-time reconciliation of every staged-media lease owned by one recovered draft.
 *
 * The ledger is the single authority for which artifacts a draft owns; this snapshot is what a
 * re-opened draft uses to decide whether its media references can still be promoted.
 */
data class DraftMediaReconciliation(
    val draftId: DraftId,
    val records: List<DraftStagedMediaRef>,
) {
    /** Records whose staged bytes are gone; their references can never be promoted. */
    val missing: List<DraftStagedMediaRef>
        get() = records.filterNot { it.stagedBytesPresent }
}

/**
 * Typed failure for a draft whose staged media bytes no longer exist.
 *
 * Rust would later reject the commit with `attachment_file_missing_after_promote`, but that code
 * does not distinguish "draft lost its staging" from other attachment errors; this carrier names
 * the recoverable condition and the destinations that must be re-attached or removed.
 */
class RecoverableDraftFailure(
    val missingDestinations: List<String>,
) : IllegalStateException(
        "Recovered draft references staged media that no longer exist: " +
            missingDestinations.joinToString(),
    ) {
    init {
        require(missingDestinations.isNotEmpty()) {
            "RecoverableDraftFailure requires at least one missing destination"
        }
    }

    /** Stable code matching the engine-failure vocabulary presentation contract. */
    val code: String
        get() = CODE

    companion object {
        const val CODE = "draft_staged_media_missing"
    }
}
