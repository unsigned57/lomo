package com.lomo.data.engine

/**
 * Opaque page cursor for Direct-root `listChildren` keyset pagination, structurally identical to
 * the Rust `HistoryPageCursor` law: a cursor is positional evidence bound to one listing context,
 * never a count of rows to skip.
 *
 * Wire form `l1.<context>.<membership>.<boundIndex>` stays inside the protocol identifier
 * alphabet: `MetadataPage.next_cursor` crosses the FFI boundary as a `CapabilityToken`
 * (1..=128 bytes of ASCII letters, digits, `-`, `_`, `.`, `:`), so a JSON or path-bearing
 * encoding is rejected at the core edge before it can poison the batch result. The minted
 * string is at most `3 + 16 + 1 + 32 + 1 + 10 = 63` bytes — every fragment is lowercase hex
 * or a decimal index.
 *
 * - `boundIndex` is the keyset bound: the sorted-set position of the last emitted child. It is
 *   meaningful only under the pinned membership, so resuming is exact — never shifted.
 * - `context` binds the cursor to the one grant root and directory it was minted for; a cursor
 *   replayed against a different listing context is invalid, not "page one".
 * - `membership` is the high-water analog: the sorted child-name set folded into a digest at
 *   mint time. Any membership change between pages — add, remove, rename — moves the
 *   fingerprint and the cursor fails closed `stale_cursor` instead of silently re-paging a
 *   shifted set. Child bytes are not part of the ordering context: content changes do not
 *   invalidate the bound (per-document freshness is verified at read, not at listing).
 */
internal object DirectListPageCursor {
    private const val WIRE_PREFIX = "l1"
    private const val CONTEXT_HEX = 16
    private const val MEMBERSHIP_HEX = 32
    private val WIRE = Regex("$WIRE_PREFIX\\.[0-9a-f]{$CONTEXT_HEX}\\.[0-9a-f]{$MEMBERSHIP_HEX}\\.[0-9]+")

    /**
     * Mints the cursor resuming after `sortedChildNames[boundIndex]`. Callers pass the sorted
     * index of the last emitted child — decimal on the wire, bounded by the set itself.
     */
    fun mint(rootId: String, directory: String, sortedChildNames: List<String>, boundIndex: Int): String =
        "$WIRE_PREFIX.${contextFingerprint(rootId, directory)}." +
            "${membershipFingerprint(sortedChildNames)}.$boundIndex"

    /**
     * Decodes [raw] and validates it against the listing context, returning the sorted-set
     * index the next page resumes after.
     *
     * Fails closed, never degrades to an offset: `invalid_page_cursor` when [raw] cannot be
     * decoded (including the retired decimal-offset and JSON forms), binds a different root or
     * directory, or indexes past the pinned set; `stale_cursor` when the directory's name set
     * drifted since the cursor was minted.
     */
    fun resolveBoundIndex(raw: String, rootId: String, directory: String, sortedChildNames: List<String>): Int {
        val decoded = decode(raw)
        if (decoded.context != contextFingerprint(rootId, directory)) {
            throw invalidCursor("page cursor does not belong to this grant root and directory listing")
        }
        if (decoded.membership != membershipFingerprint(sortedChildNames)) {
            throw DirectRootAccessException(
                category = "validation",
                code = "stale_cursor",
                diagnostic = "page cursor membership evidence no longer matches the directory",
            )
        }
        return decoded.resolveBound(sortedChildNames.size)
    }

    private data class DecodedCursor(val context: String, val membership: String, val boundIndex: Int) {
        fun resolveBound(childCount: Int): Int {
            if (boundIndex !in 0 until childCount) {
                throw invalidCursor("page cursor bound names a child absent from the fingerprinted set")
            }
            return boundIndex
        }
    }

    private fun decode(raw: String): DecodedCursor {
        if (!WIRE.matches(raw)) throw invalidCursor("page cursor cannot be decoded")
        val segments = raw.split('.')
        val boundIndex = segments[3].toIntOrNull()
        if (boundIndex == null) {
            throw invalidCursor("page cursor bound names a child absent from the fingerprinted set")
        }
        return DecodedCursor(segments[1], segments[2], boundIndex)
    }

    /**
     * Folds (root, directory) into the context binding. The raw identifiers stay off the wire:
     * only their digest crosses, so cursor length never scales with path depth.
     */
    private fun contextFingerprint(rootId: String, directory: String): String = "$rootId\u0000$directory"
        .toByteArray(Charsets.UTF_8)
        .sha256Hex()
        .take(CONTEXT_HEX)

    /**
     * Folds the sorted child-name set into staleness evidence. NUL cannot appear in a
     * directory entry name, so the join is unambiguous.
     */
    private fun membershipFingerprint(sortedChildNames: List<String>): String = sortedChildNames
        .joinToString(separator = "\u0000")
        .toByteArray(Charsets.UTF_8)
        .sha256Hex()
        .take(MEMBERSHIP_HEX)

    private fun invalidCursor(diagnostic: String, cause: Throwable? = null): DirectRootAccessException =
        DirectRootAccessException(
            category = "validation",
            code = "invalid_page_cursor",
            diagnostic = diagnostic,
            cause = cause,
        )
}
