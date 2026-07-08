package com.lomo.data.repository

import com.lomo.domain.usecase.MigrationSettingsSummary
import com.lomo.domain.model.MediaFileExtensions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


internal const val MIGRATION_ARCHIVE_VERSION = 1
internal const val MANIFEST_ENTRY = "manifest.json"
internal const val MAIN_NOTES_PREFIX = "notes/main/"
internal const val TRASH_NOTES_PREFIX = "notes/trash/"
internal const val IMAGE_MEDIA_PREFIX = "media/images/"
internal const val VOICE_MEDIA_PREFIX = "media/voice/"

internal val migrationJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

@Serializable
internal data class MigrationArchiveManifest(
    val version: Int = MIGRATION_ARCHIVE_VERSION,
    val noteCount: Int,
    val trashCount: Int,
    val imageCount: Int,
    val voiceCount: Int,
)

data class MigrationArchiveImportBudgets(
    val maxCompressedArchiveBytes: Long,
    val maxEntries: Int,
    val maxUncompressedBytes: Long,
    val maxCompressionRatio: Int,
    val maxManifestBytes: Int,
    val maxMarkdownEntryBytes: Int,
) {
    constructor() : this(
        maxCompressedArchiveBytes = DEFAULT_MAX_COMPRESSED_ARCHIVE_BYTES,
        maxEntries = DEFAULT_MAX_ARCHIVE_ENTRIES,
        maxUncompressedBytes = DEFAULT_MAX_UNCOMPRESSED_ARCHIVE_BYTES,
        maxCompressionRatio = DEFAULT_MAX_COMPRESSION_RATIO,
        maxManifestBytes = DEFAULT_MAX_MANIFEST_BYTES,
        maxMarkdownEntryBytes = DEFAULT_MAX_MARKDOWN_ENTRY_BYTES,
    )

    init {
        require(maxCompressedArchiveBytes > 0L) { "Migration archive compressed byte budget must be positive" }
        require(maxEntries > 0) { "Migration archive entry count budget must be positive" }
        require(maxUncompressedBytes > 0L) { "Migration archive uncompressed byte budget must be positive" }
        require(maxCompressionRatio > 0) { "Migration archive compression ratio budget must be positive" }
        require(maxManifestBytes > 0) { "Migration archive manifest byte budget must be positive" }
        require(maxMarkdownEntryBytes > 0) { "Migration archive markdown byte budget must be positive" }
    }
}

internal interface MigrationSettingsRestoreValidator {
    suspend fun validateRestore(snapshot: MigrationSettingsSnapshot): MigrationSettingsValidationReport
}

internal fun ZipOutputStream.writeTextEntry(
    name: String,
    text: String,
) {
    writeBytesEntry(name, text.toByteArray(Charsets.UTF_8))
}

internal fun ZipOutputStream.writeBytesEntry(
    name: String,
    bytes: ByteArray,
) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

internal fun ZipEntry.requireArchiveFilename(prefix: String): String =
    requireSafeArchiveFilename(name.removePrefix(prefix))

internal fun ZipEntry.requireArchiveMediaFilename(
    prefix: String,
    category: WorkspaceMediaCategory,
): String =
    requireSafeArchiveMediaFilename(
        filename = name.removePrefix(prefix),
        category = category,
    )

internal fun requireSafeArchiveFilename(filename: String): String {
    val normalized = filename.trim()
    require(normalized.isNotBlank()) { "Migration archive entry filename must not be blank" }
    require('/' !in normalized && '\\' !in normalized) {
        "Migration archive entry filename must not contain paths"
    }
    require(normalized != "." && normalized != "..") { "Migration archive entry filename must not be relative" }
    return normalized
}

internal fun requireSafeArchiveMediaFilename(
    filename: String,
    category: WorkspaceMediaCategory,
): String {
    val normalized = requireSafeArchiveFilename(filename)
    when (category) {
        WorkspaceMediaCategory.IMAGE ->
            require(MediaFileExtensions.hasImageExtension(normalized)) {
                "Migration archive image media entry must use an image filename: $normalized"
            }
        WorkspaceMediaCategory.VOICE ->
            require(MediaFileExtensions.hasAudioExtension(normalized)) {
                "Migration archive voice media entry must use a voice filename: $normalized"
            }
    }
    return normalized
}

internal fun MigrationSettingsSnapshot.toSummary(): MigrationSettingsSummary =
    MigrationSettingsSummary(
        settingCount = preferences.size + sensitive.size,
        sensitiveSettingCount = sensitive.size,
    )

private const val DEFAULT_MAX_ARCHIVE_ENTRIES = 25_000
private const val DEFAULT_MAX_COMPRESSION_RATIO = 1_000
private const val DEFAULT_MAX_MANIFEST_BYTES = 64 * 1024
private const val DEFAULT_MAX_MARKDOWN_ENTRY_BYTES = 1024 * 1024
private const val MEBIBYTE_BYTES = 1024L * 1024L
private const val DEFAULT_MAX_COMPRESSED_ARCHIVE_BYTES = 512L * MEBIBYTE_BYTES
private const val DEFAULT_MAX_UNCOMPRESSED_ARCHIVE_BYTES = 2L * 1024L * MEBIBYTE_BYTES
