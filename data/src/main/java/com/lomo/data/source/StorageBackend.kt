package com.lomo.data.source

import android.net.Uri

/**
 * Strategy interface for storage operations.
 * Abstracts the difference between SAF (Storage Access Framework) and direct file system access.
 */
interface StorageBackend {
    // --- Context-aware API ---

    /**
     * List markdown files in [directory].
     * @param targetFilename If specified, only return files matching this name.
     */
    suspend fun listFilesIn(
        directory: MemoDirectoryType,
        targetFilename: String? = null,
    ): List<FileContent>

    /**
     * List file metadata (name + lastModified) without reading content.
     */
    suspend fun listMetadataIn(directory: MemoDirectoryType): List<FileMetadata>

    /**
     * List metadata with document IDs for direct URI construction (SAF optimization).
     * For direct file backend, documentId equals filename.
     */
    suspend fun listMetadataWithIdsIn(directory: MemoDirectoryType): List<FileMetadataWithId>

    /**
     * Get metadata for a single file.
     */
    suspend fun getFileMetadataIn(
        directory: MemoDirectoryType,
        filename: String,
    ): FileMetadata?

    /**
     * Read file content by filename.
     */
    suspend fun readFileIn(
        directory: MemoDirectoryType,
        filename: String,
    ): String?

    suspend fun readFile(uri: Uri): String?

    /**
     * Read file by document ID (SAF optimization).
     * For direct file backend, documentId equals filename.
     */
    suspend fun readFileByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
    ): String?

    /**
     * Read only the first up-to-[maxChars] characters of a file by filename.
     */
    suspend fun readHeadIn(
        directory: MemoDirectoryType,
        filename: String,
        maxChars: Int = 256,
    ): String?

    /**
     * Read only the first up-to-[maxChars] characters of a file by document ID.
     */
    suspend fun readHeadByDocumentIdIn(
        directory: MemoDirectoryType,
        documentId: String,
        maxChars: Int = 256,
    ): String?

    /**
     * Save content to a file.
     */
    suspend fun saveFileIn(
        directory: MemoDirectoryType,
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String?

    /**
     * Delete a file.
     */
    suspend fun deleteFileIn(
        directory: MemoDirectoryType,
        filename: String,
        uri: Uri? = null,
    )

    /**
     * Check if a file exists.
     */
    suspend fun existsIn(
        directory: MemoDirectoryType,
        filename: String,
    ): Boolean

    suspend fun createDirectory(name: String): String

    // --- Backward-compatible wrappers ---

    suspend fun listFiles(targetFilename: String? = null): List<FileContent> = listFilesIn(MemoDirectoryType.MAIN, targetFilename)

    suspend fun listTrashFiles(): List<FileContent> = listFilesIn(MemoDirectoryType.TRASH)

    suspend fun listMetadata(): List<FileMetadata> = listMetadataIn(MemoDirectoryType.MAIN)

    suspend fun listTrashMetadata(): List<FileMetadata> = listMetadataIn(MemoDirectoryType.TRASH)

    suspend fun listMetadataWithIds(): List<FileMetadataWithId> = listMetadataWithIdsIn(MemoDirectoryType.MAIN)

    suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId> = listMetadataWithIdsIn(MemoDirectoryType.TRASH)

    suspend fun getFileMetadata(filename: String): FileMetadata? = getFileMetadataIn(MemoDirectoryType.MAIN, filename)

    suspend fun getTrashFileMetadata(filename: String): FileMetadata? = getFileMetadataIn(MemoDirectoryType.TRASH, filename)

    suspend fun readFile(filename: String): String? = readFileIn(MemoDirectoryType.MAIN, filename)

    suspend fun readTrashFile(filename: String): String? = readFileIn(MemoDirectoryType.TRASH, filename)

    suspend fun readFileByDocumentId(documentId: String): String? = readFileByDocumentIdIn(MemoDirectoryType.MAIN, documentId)

    suspend fun readTrashFileByDocumentId(documentId: String): String? = readFileByDocumentIdIn(MemoDirectoryType.TRASH, documentId)

    suspend fun readHead(
        filename: String,
        maxChars: Int = 256,
    ): String? = readHeadIn(MemoDirectoryType.MAIN, filename, maxChars)

    suspend fun readHeadByDocumentId(
        documentId: String,
        maxChars: Int = 256,
    ): String? = readHeadByDocumentIdIn(MemoDirectoryType.MAIN, documentId, maxChars)

    suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String? = saveFileIn(MemoDirectoryType.MAIN, filename, content, append, uri)

    suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean = true,
    ) {
        saveFileIn(MemoDirectoryType.TRASH, filename, content, append, uri = null)
    }

    suspend fun deleteFile(
        filename: String,
        uri: Uri? = null,
    ) {
        deleteFileIn(MemoDirectoryType.MAIN, filename, uri)
    }

    suspend fun deleteTrashFile(filename: String) {
        deleteFileIn(MemoDirectoryType.TRASH, filename, uri = null)
    }

    suspend fun exists(filename: String): Boolean = existsIn(MemoDirectoryType.MAIN, filename)

    suspend fun trashExists(filename: String): Boolean = existsIn(MemoDirectoryType.TRASH, filename)
}

/**
 * Strategy interface for image storage operations.
 * Separated from main storage as image directory may differ from memo directory.
 */
interface ImageStorageBackend {
    /**
     * Save an image from a source URI to the image directory.
     * @param sourceUri The source image URI (e.g., from picker).
     * @param filename Target filename to save as.
     * @return The saved filename.
     */
    suspend fun saveImage(
        sourceUri: Uri,
        filename: String,
    )

    /**
     * List all image files.
     * @return List of pairs (filename, uriString).
     */
    suspend fun listImageFiles(): List<Pair<String, String>>

    /**
     * Delete an image file.
     */
    suspend fun deleteImage(filename: String)
}

/**
 * Strategy interface for voice memo storage operations.
 */
interface VoiceStorageBackend {
    /**
     * Create a new voice memo file.
     * @param filename Desired filename (without extension? or with? let's say without, or just filename).
     * actually, let's say "filename" is the base name, and "extension" is sep.
     * Or just "filename" including extension.
     * Let's use filename (with extension) to be explicit.
     */
    suspend fun createVoiceFile(filename: String): Uri

    /**
     * Delete a voice memo file.
     * @param filename Filename.
     */
    suspend fun deleteVoiceFile(filename: String)
}
