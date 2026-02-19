package com.lomo.data.source

import android.net.Uri

/**
 * Strategy interface for storage operations.
 * Abstracts the difference between SAF (Storage Access Framework) and direct file system access.
 */
interface StorageBackend {
    // --- File listing ---

    /**
     * List all markdown files in the root directory.
     * @param targetFilename If specified, only return files matching this name.
     * @return List of file contents with metadata.
     */
    suspend fun listFiles(targetFilename: String? = null): List<FileContent>

    /**
     * List all markdown files in the trash directory.
     */
    suspend fun listTrashFiles(): List<FileContent>

    /**
     * List file metadata (name + lastModified) without reading content.
     * More efficient for sync operations.
     */
    suspend fun listMetadata(): List<FileMetadata>

    /**
     * List trash file metadata.
     */
    suspend fun listTrashMetadata(): List<FileMetadata>

    /**
     * List metadata with document IDs for direct URI construction (SAF optimization).
     * For direct file backend, documentId equals filename.
     */
    suspend fun listMetadataWithIds(): List<FileMetadataWithId>

    /**
     * List trash metadata with document IDs.
     */
    suspend fun listTrashMetadataWithIds(): List<FileMetadataWithId>

    // --- File reading ---

    /**
     * Get metadata for a single file.
     */
    suspend fun getFileMetadata(filename: String): FileMetadata?

    suspend fun getTrashFileMetadata(filename: String): FileMetadata?

    /**
     * Read file content by filename.
     */
    suspend fun readFile(filename: String): String?

    suspend fun readFile(uri: Uri): String?

    /**
     * Read trash file content by filename.
     */
    suspend fun readTrashFile(filename: String): String?

    /**
     * Read file by document ID (SAF optimization).
     * For direct file backend, documentId equals filename.
     */
    suspend fun readFileByDocumentId(documentId: String): String?

    /**
     * Read trash file by document ID.
     */
    suspend fun readTrashFileByDocumentId(documentId: String): String?

    /**
     * Read only the first up-to-[maxChars] characters of a file by filename.
     * Used for lightweight format detection without loading the whole file.
     */
    suspend fun readHead(filename: String, maxChars: Int = 256): String?

    /**
     * Read only the first up-to-[maxChars] characters of a file by document ID (SAF optimization).
     */
    suspend fun readHeadByDocumentId(documentId: String, maxChars: Int = 256): String?

    // --- File writing ---

    /**
     * Save content to a file.
     * @param filename Target filename.
     * @param content File content.
     * @param append If true, append to existing file; otherwise overwrite.
     */
    suspend fun saveFile(
        filename: String,
        content: String,
        append: Boolean = false,
        uri: Uri? = null,
    ): String?

    /**
     * Save content to a trash file.
     */
    suspend fun saveTrashFile(
        filename: String,
        content: String,
        append: Boolean = true,
    )

    // --- File deletion ---

    /**
     * Delete a file from the root directory.
     */
    suspend fun deleteFile(
        filename: String,
        uri: Uri? = null,
    )

    /**
     * Delete a file from the trash directory.
     */
    suspend fun deleteTrashFile(filename: String)

    // --- File existence ---

    /**
     * Check if a file exists in the root directory.
     */
    suspend fun exists(filename: String): Boolean

    /**
     * Check if a file exists in the trash directory.
     */
    suspend fun trashExists(filename: String): Boolean

    suspend fun createDirectory(name: String): String
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
