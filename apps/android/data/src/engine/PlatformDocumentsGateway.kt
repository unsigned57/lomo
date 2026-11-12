package com.lomo.data.engine

import android.net.Uri
import android.provider.DocumentsContract
import com.lomo.nativebridge.ActionOutcome
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
    /**
     * Observes one target. [digest] declares how much evidence the caller needs:
     * [SnapshotDigestMode.METADATA_ONLY] stays inside provider metadata and never opens the
     * content stream, while [SnapshotDigestMode.CONTENT] hashes the document bytes into a
     * verified digest. Only actions whose evidence is consumed for content verification may
     * request CONTENT — a metadata observation must never silently read whole documents.
     */
    fun stat(treeUri: String, target: WorkspaceTarget, digest: SnapshotDigestMode): PlatformDocumentSnapshot?

    fun listChildren(treeUri: String, target: WorkspaceTarget, cursor: String?, pageSize: UInt): PlatformMetadataPage

    fun ensureDirectory(treeUri: String, path: String): PlatformDocumentSnapshot

    fun openRead(treeUri: String, path: String): PlatformReadHandle

    fun openReadByHandle(treeUri: String, path: String, documentHandle: String): PlatformReadHandle

    /**
     * Resolves the target once, runs [arbitrate] on that same live observation, and writes only
     * when the arbitration returns null (proceed). A non-null [ActionOutcome] is satisfaction —
     * the document already holds the requested durable state and no mutation is issued.
     */
    fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome

    /**
     * Streams one local file into the tree without materializing its bytes in memory, so media
     * promotion stays bounded by the copy chunk instead of the media size. The persisted document
     * is verified against the streamed bytes by digest before the write is reported successful.
     * The presence of the observed target decides create-vs-replace.
     */
    fun writeFromFile(
        treeUri: String,
        path: String,
        source: File,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome

    fun move(
        treeUri: String,
        source: String,
        target: String,
        targetTreeUri: String = treeUri,
    ): PlatformDocumentSnapshot

    fun delete(treeUri: String, path: String)
}

/** Evidence depth an observation needs; callers requiring verified content declare CONTENT. */
internal enum class SnapshotDigestMode {
    /** Provider metadata columns only; the returned snapshot digest is `null` (Unknown). */
    METADATA_ONLY,

    /** Streams the document through SHA-256 so the snapshot carries a verified digest. */
    CONTENT,
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
    /** Provider-observed display name; lets a write receipt double as the name-repair check. */
    val displayName: String? = null,
)

/**
 * Arbitration executed on the write's own live observation before any mutation lands.
 * Return a non-null [ActionOutcome] to report satisfaction (the write is skipped), or null to
 * let the write proceed. Postcondition violations throw instead of returning.
 */
internal typealias WriteArbitration = (PlatformDocumentSnapshot?) -> ActionOutcome?

internal sealed interface WriteOutcome {
    /** The arbitration proved the durable goal already holds; no write was issued. */
    data class Satisfied(val outcome: ActionOutcome) : WriteOutcome

    /** The write landed and was verified; [snapshot] is the write receipt. */
    data class Written(val snapshot: PlatformDocumentSnapshot) : WriteOutcome
}

internal data class PlatformMetadataPage(
    val items: List<PlatformDocumentSnapshot>,
    val nextCursor: String?,
    /**
     * True when the provider could not enumerate the target at all. An incomplete page must never
     * be read as a complete empty directory, or a scan would treat unreadable data as deleted.
     */
    val incomplete: Boolean = false,
)

internal data class PlatformReadHandle(val snapshot: PlatformDocumentSnapshot, val bytes: ByteArray)

/** Deterministic evidence fingerprint shared by gateway snapshots and action outputs. */
internal object PlatformActionEvidence {
    fun fingerprint(documentId: String, lastModifiedEpochMillis: Long, length: ULong): String {
        val material = "v1|$documentId|$lastModifiedEpochMillis|$length"
        // CapabilityToken alphabet: ASCII alnum + - _ . :
        return "fp." + material.sha256Hex().take(FINGERPRINT_HEX_PREFIX_LENGTH)
    }

    private const val FINGERPRINT_HEX_PREFIX_LENGTH = 48
}

internal fun String.toAndroidUri(): Uri = Uri.parse(this)

private fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()

/** The document portion is the selected directory; the tree portion retains its grant. */
internal fun selectedSafDocumentId(raw: String, uri: Uri): String =
    if ("/document/" in raw) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
