package com.lomo.domain.model

private const val MAX_DRAFT_ID_LENGTH = 128

/**
 * Durable identity of one editing draft that owns staged media before it is submitted.
 *
 * The token is minted by the draft owner (editor session, recording session) and frozen for the
 * draft's lifetime, so every media import it makes is leased to the same holder and releasing one
 * draft can never destroy bytes another draft still references. The alphabet matches the Rust
 * operation token alphabet; Kotlin must not accept a wider set.
 */
@JvmInline
value class DraftId(val value: String) {
    init {
        require(
            value.isNotBlank() &&
                value.length <= MAX_DRAFT_ID_LENGTH &&
                value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_.:" },
        ) {
            "Draft id must be a bounded nonblank ASCII protocol token"
        }
    }
}
