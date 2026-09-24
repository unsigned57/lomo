package com.lomo.domain.repository

import com.lomo.domain.model.CustomFontImportResult
import com.lomo.domain.model.CustomFontInfo
import com.lomo.domain.model.CustomFontSource
import kotlinx.coroutines.flow.Flow

/**
 * Port for managing user-imported custom font files. Implementations live in the data layer and
 * own the local filesystem layout (typically `filesDir/custom_fonts/`).
 *
 * The contract is byte/stream-based instead of taking Android `Uri` or `Typeface` types so that
 * the domain layer stays Android-free (see ARCHITECTURE.md).
 */
interface CustomFontStore {
    /** Stream of currently imported fonts, ordered by import time. Re-emits after import/delete. */
    fun observeFonts(): Flow<List<CustomFontInfo>>

    /**
     * Streams [source] into the font directory under a unique file name derived from the
     * sanitized [originalFileName]. The byte budget is enforced on the stream itself and the
     * bytes must pass font validation before they are atomically published. Returns a typed
     * [CustomFontImportResult] — a rejected import never becomes a selectable fact.
     */
    suspend fun importFont(
        source: CustomFontSource,
        originalFileName: String,
    ): CustomFontImportResult

    /** Removes a font by id. Safe to call with an id that no longer exists. */
    suspend fun deleteFont(id: String)

    /**
     * Returns the absolute path of the font file backing [id], or `null` if the file is missing
     * (e.g. user cleared app data, restored partial backup). Callers must surface the missing
     * state explicitly — the data layer treats a missing file as a documented domain state.
     */
    suspend fun resolveFontPath(id: String): String?
}
