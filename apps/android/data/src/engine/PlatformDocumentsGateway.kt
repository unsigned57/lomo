package com.lomo.data.engine

import android.net.Uri
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.File

/**
 * Android document operations required by the platform-action protocol.
 *
 * Tree URIs arrive as opaque grant strings from [CapabilityRegistry]. Production uses
 * [ContentResolverPlatformDocumentsGateway]; unit tests inject a fake so postcondition/replay
 * behavior is proven without Robolectric or a live DocumentsProvider.
 */
internal interface PlatformDocumentsGateway {
    fun stat(
        treeUri: String,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot?

    fun listChildren(
        treeUri: String,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage

    fun ensureDirectory(
        treeUri: String,
        path: String,
    ): PlatformDocumentSnapshot

    fun openRead(
        treeUri: String,
        path: String,
    ): PlatformReadHandle

    fun openReadByHandle(
        treeUri: String,
        path: String,
        documentHandle: String,
    ): PlatformReadHandle

    fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot

    /**
     * Streams one local file into the tree without materializing its bytes in memory, so media
     * promotion stays bounded by the copy chunk instead of the media size. The persisted document
     * is verified against the streamed bytes by digest before the write is reported successful.
     */
    fun writeFromFile(
        treeUri: String,
        path: String,
        source: File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot

    fun move(
        treeUri: String,
        source: String,
        target: String,
    ): PlatformDocumentSnapshot

    fun delete(
        treeUri: String,
        path: String,
    )
}

internal data class PlatformDocumentSnapshot(
    val target: WorkspaceTarget,
    val kind: DocumentKind,
    val mimeType: String?,
    val length: ULong,
    val lastModifiedEpochMillis: Long,
    val documentId: String,
    /** Independently hashed SHA-256 hex, or `null` when bytes were not read. */
    val digest: String?,
)

internal data class PlatformMetadataPage(
    val items: List<PlatformDocumentSnapshot>,
    val nextCursor: String?,
    /**
     * True when the provider could not enumerate the target at all. An incomplete page must never
     * be read as a complete empty directory, or a scan would treat unreadable data as deleted.
     */
    val incomplete: Boolean = false,
)

internal data class PlatformReadHandle(
    val snapshot: PlatformDocumentSnapshot,
    val bytes: ByteArray,
)

/** Deterministic evidence fingerprint shared by gateway snapshots and action outputs. */
internal object PlatformActionEvidence {
    fun fingerprint(
        documentId: String,
        lastModifiedEpochMillis: Long,
        length: ULong,
    ): String {
        val material = "v1|$documentId|$lastModifiedEpochMillis|$length"
        // CapabilityToken alphabet: ASCII alnum + - _ . :
        return "fp." + material.sha256Hex().take(FINGERPRINT_HEX_PREFIX_LENGTH)
    }

    private const val FINGERPRINT_HEX_PREFIX_LENGTH = 48
}

internal fun String.toAndroidUri(): Uri = Uri.parse(this)

private fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()
