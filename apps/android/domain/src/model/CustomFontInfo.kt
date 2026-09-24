package com.lomo.domain.model

import java.io.InputStream

/**
 * Metadata for a user-imported custom font.
 *
 * @property id Stable identifier (filename inside the data-layer storage directory). Persisted
 *   inside [FontPreference.UserImported].
 * @property displayName Human-readable label derived from the original source filename.
 * @property sizeBytes Size of the imported font file in bytes. Useful for the management UI.
 * @property nameState Whether [displayName] came from the preserved original name or from a
 *   legacy import whose original name was never recorded — the UI must present the migration
 *   state rather than pretend the id is a name.
 */
data class CustomFontInfo(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val nameState: CustomFontNameState = CustomFontNameState.ORIGINAL,
)

/** Provenance of a stored font's display name. */
enum class CustomFontNameState {
    /** Name preserved from the user's original file name. */
    ORIGINAL,

    /** File predates named imports; the id is the only recorded name. */
    LEGACY_IMPORT,
}

/** Opens the import byte stream; invoked on an IO dispatcher by the store. */
fun interface CustomFontSource {
    fun openStream(): InputStream?
}

/** Terminal result of one font import: published fact or typed rejection. */
sealed interface CustomFontImportResult {
    data class Imported(
        val info: CustomFontInfo,
    ) : CustomFontImportResult

    data class Rejected(
        val reason: CustomFontRejection,
    ) : CustomFontImportResult
}

enum class CustomFontRejection {
    /** Extension is not a supported font container. */
    UNSUPPORTED_TYPE,

    /** The stream exceeded the import byte budget. */
    OVERSIZED,

    /** The bytes do not validate as a font (bad magic or failed platform parse). */
    INVALID_FONT,

    /** The source could not be opened or read. */
    UNREADABLE,
}
