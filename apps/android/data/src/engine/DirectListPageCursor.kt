package com.lomo.data.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Opaque page cursor for Direct-root `listChildren` keyset pagination, structurally identical to
 * the Rust `HistoryPageCursor` law: a cursor is positional evidence bound to one listing context,
 * never a count of rows to skip.
 *
 * - [lastName] is the keyset bound: the next page re-enters exactly after it under the
 *   `name ASC` ordering, so pages tile the directory without repeat or gap.
 * - [rootId] + [directory] bind the bound to the one grant root and directory it was minted for; a
 *   cursor replayed against a different listing context is invalid, not "page one".
 * - [membershipFingerprint] is the high-water analog: the sorted child-name set folded into a
 *   digest at mint time. Any membership change between pages — add, remove, rename — moves the
 *   fingerprint and the cursor fails closed `stale_cursor` instead of silently re-paging a
 *   shifted set. Child bytes are not part of the ordering context: content changes do not
 *   invalidate the bound (per-document freshness is verified at read, not at listing).
 */
@Serializable
internal data class DirectListPageCursor(
    @SerialName("root_id") val rootId: String,
    @SerialName("directory") val directory: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("membership_fingerprint") val membershipFingerprint: String,
) {
    /** Encodes the cursor as compact JSON; the string is opaque to platform-action consumers. */
    fun encode(): String = cursorJson.encodeToString(serializer(), this)

    companion object {
        private val cursorJson = Json

        /**
         * Folds the sorted child-name set into staleness evidence. NUL cannot appear in a
         * directory entry name, so the join is unambiguous.
         */
        fun membershipFingerprint(sortedChildNames: List<String>): String =
            sortedChildNames
                .joinToString(separator = "\u0000")
                .toByteArray(Charsets.UTF_8)
                .sha256Hex()

        /**
         * Decodes [raw] and validates it against the listing context, returning the keyset bound
         * (the last emitted child name).
         *
         * Fails closed, never degrades to an offset: `invalid_page_cursor` when [raw] cannot be
         * decoded (including the retired decimal-offset form), binds a different root or
         * directory, or carries an empty bound; `stale_cursor` when the directory's name set
         * drifted since the cursor was minted.
         */
        fun resolveBound(
            raw: String,
            rootId: String,
            directory: String,
            membershipFingerprint: String,
        ): String {
            val decoded = decode(raw)
            if (decoded.rootId != rootId ||
                decoded.directory != directory ||
                decoded.lastName.isEmpty()
            ) {
                throw invalidCursor(
                    "page cursor does not belong to this grant root and directory listing",
                )
            }
            if (decoded.membershipFingerprint != membershipFingerprint) {
                throw DirectRootAccessException(
                    category = "validation",
                    code = "stale_cursor",
                    diagnostic = "page cursor membership evidence no longer matches the directory",
                )
            }
            return decoded.lastName
        }

        private fun decode(raw: String): DirectListPageCursor =
            try {
                cursorJson.decodeFromString(serializer(), raw)
            } catch (failure: SerializationException) {
                throw invalidCursor("page cursor cannot be decoded", failure)
            } catch (failure: IllegalArgumentException) {
                throw invalidCursor("page cursor cannot be decoded", failure)
            }

        private fun invalidCursor(
            diagnostic: String,
            cause: Throwable? = null,
        ): DirectRootAccessException =
            DirectRootAccessException(
                category = "validation",
                code = "invalid_page_cursor",
                diagnostic = diagnostic,
                cause = cause,
            )
    }
}
