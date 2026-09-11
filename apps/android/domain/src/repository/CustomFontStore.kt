package com.lomo.domain.repository

import com.lomo.domain.model.CustomFontInfo
import kotlinx.coroutines.flow.Flow

/**
 * Port for managing user-imported custom font files. Implementations live in the data layer and
 * own the local filesystem layout (typically `context.filesDir/custom_fonts/`).
 *
 * The contract is intentionally byte-based instead of taking Android `Uri` types so that the
 * domain layer stays Android-free (see ARCHITECTURE.md).
 */
interface CustomFontStore {
    /** Stream of currently imported fonts, ordered by import time. Re-emits after import/delete. */
    fun observeFonts(): Flow<List<CustomFontInfo>>

    /**
     * Persists [contents] under a new font id. [originalFileName] is used to derive a human-visible
     * display name and to validate the extension (`.ttf` / `.otf`). Returns the resulting info on
     * success, or `null` if validation rejected the file.
     */
    suspend fun importFont(contents: ByteArray, originalFileName: String): CustomFontInfo?

    /** Removes a font by id. Safe to call with an id that no longer exists. */
    suspend fun deleteFont(id: String)

    /**
     * Returns the absolute path of the font file backing [id], or `null` if the file is missing
     * (e.g. user cleared app data, restored partial backup). Callers must handle null by falling
     * back to the system default — the data layer treats missing files as a documented domain
     * state, not an error.
     */
    suspend fun resolveFontPath(id: String): String?
}
