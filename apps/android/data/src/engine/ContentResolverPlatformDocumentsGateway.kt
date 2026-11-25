package com.lomo.data.engine

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.lomo.domain.model.parseContentUriAuthority
import com.lomo.domain.model.stripContentUriUserId
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Acquires a leased provider connection for a single SAF operation sequence. */
internal interface SafProviderConnector {
    fun <T> connect(resolver: ContentResolver, tree: Uri, operation: (ContentResolver) -> T): T
}

/**
 * Production [PlatformDocumentsGateway] over [ContentResolver] / DocumentsContract.
 *
 * Tree URI strings come from [CapabilityRegistry]; conversion to [Uri] stays inside this edge.
 * Path walking and listing memos live in [DocumentPathResolver], document observation in
 * [DocumentSnapshotQueries], and create/write/move mechanics in [DocumentWriter]; the gateway
 * itself only orchestrates the public port methods.
 */
internal class ContentResolverPlatformDocumentsGateway(
    private val contentResolver: ContentResolver,
    private val transientRetryDelaysMillis: LongArray = DEFAULT_TRANSIENT_RETRY_DELAYS_MILLIS,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
    private val providerConnector: SafProviderConnector? = null,
) : PlatformDocumentsGateway {
    private val state = SafDocumentState()
    private val resolverOperations = SafDocumentOperations(contentResolver, state, boundToProvider = false)

    // API 26-28 execute the same operations through ContentResolver; API 29 introduced wrap.
    // Each modern operation owns one connection until all cursors/streams have closed. There
    // is no process-global provider lease to leak, evict under a reader, or reuse after death.
    private fun <T> connected(treeUri: String, operation: (SafDocumentOperations) -> T): T = when {
        providerConnector != null ->
            providerConnector.connect(contentResolver, treeUri.toAndroidUri()) { resolver ->
                operation(SafDocumentOperations(resolver, state, boundToProvider = true))
            }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            withDocumentProviderConnection(contentResolver, treeUri.toAndroidUri()) { resolver ->
                operation(SafDocumentOperations(resolver, state, boundToProvider = true))
            }

        else -> operation(resolverOperations)
    }

    /**
     * Retries only read-only provider operations across cold-start SAF provider warmup states
     * (`EXTRA_LOADING`, null provider client, `RemoteException`/`DeadObjectException`, or transient
     * incomplete cursors). Writes/creates/deletes/moves are never retried automatically here.
     */
    private fun <T> connectedReadOnly(treeUri: String, operation: (SafDocumentOperations) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return connected(treeUri, operation)
            } catch (error: Exception) {
                val delayMs = transientRetryDelaysMillis.getOrNull(attempt)
                if (delayMs == null || !error.isTransientSafReadFailure() || Thread.currentThread().isInterrupted) {
                    throw error
                }
                if (delayMs > 0L) {
                    sleepMillis(delayMs)
                }
                attempt += 1
            }
        }
    }

    override fun stat(treeUri: String, target: WorkspaceTarget, digest: SnapshotDigestMode) =
        connectedReadOnly(treeUri) { it.stat(treeUri, target, digest) }

    override fun listChildren(treeUri: String, target: WorkspaceTarget, cursor: String?, pageSize: UInt) =
        if (cursor == null) {
            connectedReadOnly(treeUri) { it.listChildren(treeUri, target, null, pageSize) }
        } else {
            state.pages.resume(treeUri, target, cursor, pageSize)
        }

    override fun ensureDirectory(treeUri: String, path: String) =
        connected(treeUri) { it.ensureDirectory(treeUri, path) }

    override fun openRead(treeUri: String, path: String) = connectedReadOnly(treeUri) { it.openRead(treeUri, path) }

    override fun openReadByHandle(treeUri: String, path: String, documentHandle: String) =
        connectedReadOnly(treeUri) { it.openReadByHandle(treeUri, path, documentHandle) }

    override fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ) = connected(treeUri) { it.writeFromExchange(treeUri, path, bytes, mode, mimeType, arbitrate) }

    override fun writeFromFile(
        treeUri: String,
        path: String,
        source: File,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ) = connected(treeUri) { it.writeFromFile(treeUri, path, source, mimeType, arbitrate) }

    override fun move(treeUri: String, source: String, target: String, targetTreeUri: String) =
        connected(treeUri) { it.move(treeUri, source, target, targetTreeUri) }

    override fun delete(treeUri: String, path: String) = connected(treeUri) { it.delete(treeUri, path) }

    internal companion object {
        val DEFAULT_TRANSIENT_RETRY_DELAYS_MILLIS = longArrayOf(50L, 150L, 300L)

        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

private fun Exception.isTransientSafReadFailure(): Boolean = when (this) {
    is PlatformActionAccessException ->
        code == "metadata_enumeration_loading" || code == "metadata_enumeration_incomplete"

    is android.os.RemoteException -> true

    is IOException ->
        message?.startsWith("Document provider is unavailable:") == true

    else -> false
}

private class DocumentListingCache {
    private val childListings = ConcurrentHashMap<ListingKey, ConcurrentHashMap<String, String>>()

    fun snapshot(treeUri: String, parentDocId: String): Map<String, String>? =
        childListings[ListingKey(treeUri, parentDocId)]

    /** Record a create under [parentDocId] so the memo answers the new name without a re-list. */
    fun noteChildAdded(treeUri: String, parentDocId: String, name: String, childDocId: String) {
        childListings.computeIfPresent(ListingKey(treeUri, parentDocId)) { _, listing ->
            listing.apply { put(name, childDocId) }
        }
    }

    /** Record a removal under [parentDocId]; also drops the removed document's own listing. */
    fun noteChildRemoved(treeUri: String, parentDocId: String, name: String, removedDocId: String) {
        childListings.computeIfPresent(ListingKey(treeUri, parentDocId)) { _, listing ->
            listing.apply { remove(name) }
        }
        childListings.keys.removeIf { it.treeUri == treeUri && it.parentDocId == removedDocId }
    }

    /** Record a rename/move: the child leaves one parent listing and enters another. */
    fun noteChildMoved(treeUri: String, source: ListingChild, target: ListingChild) {
        noteChildRemoved(treeUri, source.parentDocId, source.name, source.documentId)
        noteChildAdded(treeUri, target.parentDocId, target.name, target.documentId)
    }

    fun rememberCompleteListing(treeUri: String, parentDocId: String, nameToDocumentId: Map<String, String>) {
        childListings[ListingKey(treeUri, parentDocId)] = ConcurrentHashMap(nameToDocumentId)
    }
}

private class SafDocumentState {
    val listings = DocumentListingCache()
    val pages = SafDirectoryPages()
    val twoPassTrees: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
}

private class SafDocumentOperations(
    private val contentResolver: ContentResolver,
    private val state: SafDocumentState,
    private val boundToProvider: Boolean,
) : PlatformDocumentsGateway {
    private val queries = DocumentSnapshotQueries(contentResolver)
    private val paths = DocumentPathResolver(contentResolver, queries, state.listings)
    private val writer = DocumentWriter(contentResolver, paths, queries, state.twoPassTrees, boundToProvider)

    private fun String.toProviderTreeUri(): Uri =
        if (boundToProvider) stripContentUriUserId(this).toAndroidUri() else toAndroidUri()

    override fun stat(
        treeUri: String,
        target: WorkspaceTarget,
        digest: SnapshotDigestMode,
    ): PlatformDocumentSnapshot? {
        val root = treeUri.toProviderTreeUri()
        return when (target) {
            is WorkspaceTarget.Root -> {
                val docId = selectedSafDocumentId(treeUri, root)
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, docId)
                queries.querySnapshot(
                    documentUri = documentUri,
                    target = WorkspaceTarget.Root,
                    documentId = docId,
                    digestMode = digest,
                )
            }

            is WorkspaceTarget.Relative ->
                paths.resolveObserved(root, treeUri, target.path) { resolved ->
                    queries.querySnapshot(
                        documentUri = resolved.uri,
                        target = target,
                        documentId = resolved.documentId,
                        digestMode = digest,
                    )
                }
        }
    }

    override fun listChildren(
        treeUri: String,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage {
        SafDirectoryPages.requirePageSize(pageSize)
        if (cursor != null) return state.pages.resume(treeUri, target, cursor, pageSize)
        val targetLabel = target.listingLabel()
        val root = treeUri.toProviderTreeUri()
        val parentDocId =
            when (target) {
                is WorkspaceTarget.Root -> selectedSafDocumentId(treeUri, root)

                is WorkspaceTarget.Relative ->
                    paths.resolveObserved(root, treeUri, target.path) { it.documentId }
                        ?: throw incompleteDirectory(targetLabel)
            }
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentDocId)
        return contentResolver.withCompleteDirectory(
            childUri,
            ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION,
            targetLabel,
        ) { queryCursor ->
            val indices = DocumentColumnIndices.from(queryCursor)
            val siblings = HashMap<String, String>()
            val items = state.pages.capture(
                sequence {
                    while (queryCursor.moveToNext()) {
                        val snapshot = indices.extractChildSnapshot(queryCursor, target)
                            ?: throw incompleteDirectory(targetLabel)
                        val name = checkNotNull(snapshot.displayName)
                        addDirectoryChild(siblings, name, snapshot.documentId, targetLabel)
                        yield(snapshot)
                    }
                },
            )
            // The entire provider observation is complete even when the FFI page is small.
            // Reuse every sibling name; large directories must not fall back to re-listing.
            paths.listings.rememberCompleteListing(treeUri, parentDocId, siblings)
            state.pages.first(treeUri, target, items, pageSize)
        }
    }

    override fun ensureDirectory(treeUri: String, path: String): PlatformDocumentSnapshot =
        writer.ensureDirectory(treeUri, path)

    override fun openRead(treeUri: String, path: String): PlatformReadHandle {
        val root = treeUri.toProviderTreeUri()
        val (resolved, snapshot) =
            paths.resolveObserved(root, treeUri, path) { document ->
                queries.querySnapshot(
                    documentUri = document.uri,
                    target = WorkspaceTarget.Relative(path),
                    documentId = document.documentId,
                    digestMode = SnapshotDigestMode.METADATA_ONLY,
                )?.let { document to it }
            } ?: errorNotFound("Missing document: $path")
        val bytes =
            contentResolver.openInputStream(resolved.uri)?.use { input ->
                // behavior-contract: full-load-ok: complete payload required for parse/hash
                input.readBytes()
            }
                ?: errorIo("openInputStream returned null for $path")
        return PlatformReadHandle(
            snapshot =
            snapshot.copy(
                digest = bytes.sha256Hex(),
                length = bytes.size.toULong(),
            ),
            bytes = bytes,
        )
    }

    override fun openReadByHandle(treeUri: String, path: String, documentHandle: String): PlatformReadHandle {
        val root = treeUri.toProviderTreeUri()
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, documentHandle)
        val snapshot =
            queries.querySnapshot(
                documentUri = documentUri,
                target = WorkspaceTarget.Relative(path),
                documentId = documentHandle,
                digestMode = SnapshotDigestMode.METADATA_ONLY,
            ) ?: errorNotFound("Missing document metadata for opaque handle")
        val bytes =
            contentResolver.openInputStream(documentUri)?.use { input ->
                // behavior-contract: full-load-ok: complete payload required for parse/hash
                input.readBytes()
            }
                ?: errorIo("openInputStream returned null for opaque document handle")
        return PlatformReadHandle(
            snapshot = snapshot.copy(digest = bytes.sha256Hex(), length = bytes.size.toULong()),
            bytes = bytes,
        )
    }

    override fun writeFromExchange(
        treeUri: String,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome = writer.writeToDocument(treeUri, path, ByteArrayInputStream(bytes), mode, mimeType, arbitrate)

    override fun writeFromFile(
        treeUri: String,
        path: String,
        source: File,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome = writer.writeToDocument(treeUri, path, source.inputStream().buffered(), null, mimeType, arbitrate)

    override fun move(
        treeUri: String,
        source: String,
        target: String,
        targetTreeUri: String,
    ): PlatformDocumentSnapshot = writer.move(treeUri, source, target, targetTreeUri)

    override fun delete(treeUri: String, path: String) {
        val root = treeUri.toProviderTreeUri()
        val chain = paths.resolveWriteChain(root, treeUri, path, mode = null)
        val leaf = chain.leaf ?: return
        if (DocumentsContract.deleteDocument(contentResolver, leaf.uri)) {
            paths.listings.noteChildRemoved(treeUri, chain.parentDocId, path.substringAfterLast('/'), leaf.documentId)
        }
    }
}

/**
 * Segment-by-segment path resolution plus the child-listing memo.
 *
 * Last live child listing per (tree, parent): a name-resolution accelerator only. Positive hits
 * still observe the document live afterwards. A read miss has a live parent listing and live
 * name/kind observations for every cached ancestor. Stale ancestors trigger a new verified walk
 * against the provider ([reverifyPath]). Mutations the gateway itself commits
 * update the affected parent listing in place, and live listings fetched by bypass walks
 * repopulate the memo, so the cache never reads stale names.
 */
private class DocumentPathResolver(
    private val contentResolver: ContentResolver,
    private val queries: DocumentSnapshotQueries,
    val listings: DocumentListingCache,
) {

    /** Per-walk record of the directory hops the listing memo answered. */
    class ResolveTrace {
        // behavior-contract: mutable-payload-ok: walk-local evidence, never published or shared
        internal val memoHops = mutableListOf<MemoHop>()
    }

    /** One hop the listing memo answered: the directory [name] resolved to [documentId]. */
    data class MemoHop(val name: String, val documentId: String)

    fun resolvePath(
        root: Uri,
        treeUri: String,
        path: String,
        bypassListings: Boolean = false,
        trace: ResolveTrace? = null,
    ): ResolvedDocument? {
        var parentDocId = selectedSafDocumentId(treeUri, root)
        val segments = path.split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return null
        var current: ResolvedDocument? = null
        for (segment in segments) {
            current = findChild(root, treeUri, parentDocId, segment, bypassListings, trace) ?: return null
            parentDocId = current.documentId
        }
        return current
    }

    /**
     * Resolve [path] and pass the resolved document to [observe], holding the re-verify rule in
     * one place: when the memo walk consulted cached evidence and still missed, or when the
     * observation of the resolved document itself comes back null, retry once against the live
     * provider before surfacing null.
     */
    fun <T> resolveObserved(root: Uri, treeUri: String, path: String, observe: (ResolvedDocument) -> T?): T? {
        val trace = ResolveTrace()
        val resolved =
            resolvePath(root, treeUri, path, trace = trace)
                ?: return if (trace.memoHops.all { hop -> directoryStillNamed(root, hop) }) {
                    // A miss always comes from a live listing of the parent where it occurred.
                    // It is authoritative once every memo-resolved ancestor still names its path.
                    null
                } else {
                    reverifyPath(root, treeUri, path)?.let(observe)
                }
        return observe(resolved)
            ?: reverifyPath(root, treeUri, path)?.let(observe)
    }

    /**
     * Live re-resolution with the write chain's law: each parent handle is observed by name and
     * kind (re-listing only the parent of a stale handle) and the leaf's own parent is listed live.
     */
    private fun reverifyPath(root: Uri, treeUri: String, path: String): ResolvedDocument? {
        val segments = path.split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) return null
        var parentDocId = selectedSafDocumentId(treeUri, root)
        for (segment in segments.dropLast(1)) {
            parentDocId = verifiedParent(root, treeUri, parentDocId, segment) ?: return null
        }
        return liveListing(root, treeUri, parentDocId)[segments.last()]?.let { resolvedDocument(root, it) }
    }

    private fun directoryStillNamed(root: Uri, hop: MemoHop): Boolean {
        val document = resolvedDocument(root, hop.documentId)
        val observed = queries.queryPresence(document.uri, WorkspaceTarget.Relative(hop.name), hop.documentId)
        return observed is DocumentPresence.Present &&
            observed.snapshot.kind == DocumentKind.DIRECTORY &&
            observed.snapshot.displayName == hop.name
    }

    /**
     * Parent handles are hints until their directory kind and name are verified live. This
     * observes one directory instead of transferring every sibling at every hop. Replacement
     * and deletion observe the leaf live. Create-only requests may allocate from a name hint;
     * the provider's new-document receipt and collision checks own the final naming verdict.
     */
    fun resolveWriteChain(root: Uri, treeUri: String, path: String, mode: WriteMode?): WriteChain {
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty()) { "write path must not be empty" }
        var parentDocId = selectedSafDocumentId(treeUri, root)
        val parents = segments.dropLast(1)
        for ((index, segment) in parents.withIndex()) {
            val childDocId = verifiedParent(root, treeUri, parentDocId, segment)
                ?: return WriteChain(
                    leaf = null,
                    parentDocId = parentDocId,
                    missingDirs = parents.drop(index),
                )
            parentDocId = childDocId
        }
        return WriteChain(
            leaf = resolveLeaf(root, treeUri, parentDocId, segments.last(), mode),
            parentDocId = parentDocId,
            missingDirs = emptyList(),
        )
    }

    private fun resolveLeaf(
        root: Uri,
        treeUri: String,
        parentDocId: String,
        name: String,
        mode: WriteMode?,
    ): ResolvedDocument? {
        val cached = listings.snapshot(treeUri, parentDocId)
        val cachedId = cached?.get(name)
        if (cachedId != null) {
            val candidate = resolvedDocument(root, cachedId)
            val observed = queries.queryPresence(candidate.uri, WorkspaceTarget.Relative(name), cachedId)
            if (observed is DocumentPresence.Present && observed.snapshot.displayName == name) {
                // A live name observation selects the handle; the writer still reads its
                // current content for CAS arbitration before opening any output stream.
                return candidate
            }
        } else if (mode == WriteMode.CREATE && cached != null) {
            // Cached absence permits allocation only. Exact-name receipt and collision
            // repair arbitrate external creates; replacement/deletion must resolve live.
            return null
        }
        return liveListing(root, treeUri, parentDocId)[name]?.let { resolvedDocument(root, it) }
    }

    private fun verifiedParent(root: Uri, treeUri: String, parentDocId: String, name: String): String? {
        val cachedId = listings.snapshot(treeUri, parentDocId)?.get(name)
        if (cachedId != null) {
            val document = resolvedDocument(root, cachedId)
            val observed = queries.queryPresence(document.uri, WorkspaceTarget.Relative(name), cachedId)
            if (observed is DocumentPresence.Present &&
                observed.snapshot.kind == DocumentKind.DIRECTORY && observed.snapshot.displayName == name
            ) {
                return cachedId
            }
        }
        // A missing, renamed or replaced handle cannot anchor a write. Resolve the name
        // from the provider again; never turn a stale cached miss into directory creation.
        return liveListing(root, treeUri, parentDocId)[name]
    }

    fun findChild(
        root: Uri,
        treeUri: String,
        parentDocId: String,
        name: String,
        bypassListings: Boolean,
        trace: ResolveTrace? = null,
    ): ResolvedDocument? {
        if (!bypassListings) {
            val documentId = listings.snapshot(treeUri, parentDocId)?.get(name)
            if (documentId != null) {
                trace?.run { memoHops.add(MemoHop(name = name, documentId = documentId)) }
                return resolvedDocument(root, documentId)
            }
        }
        val listing = liveListing(root, treeUri, parentDocId)
        return listing[name]?.let { resolvedDocument(root, it) }
    }

    /**
     * Live listing of one parent. The provider's answer is always memoized — live data is the
     * freshest evidence for this parent whether or not the current walk trusted the memo.
     */
    private fun liveListing(root: Uri, treeUri: String, parentDocId: String): Map<String, String> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentDocId)
        val listing = contentResolver.withCompleteDirectory(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            "parent directory",
        ) { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val names = HashMap<String, String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: throw incompleteDirectory("parent directory")
                val documentId = cursor.getString(idIndex) ?: throw incompleteDirectory("parent directory")
                addDirectoryChild(names, name, documentId, "parent directory")
            }
            names
        }
        listings.rememberCompleteListing(treeUri, parentDocId, listing)
        return listing
    }

    private fun resolvedDocument(root: Uri, documentId: String): ResolvedDocument = ResolvedDocument(
        documentId = documentId,
        uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId),
    )
}

private data class ListingChild(val parentDocId: String, val name: String, val documentId: String)

private data class ResolvedDocument(val documentId: String, val uri: Uri)

/**
 * One live-walk observation for a write: the resolved leaf (if present), the document id the
 * create anchors to, the live listing of that parent, and the parent segments still missing.
 */
private data class WriteChain(val leaf: ResolvedDocument?, val parentDocId: String, val missingDirs: List<String>)

/**
 * Live document observation: metadata snapshots, display names and content digests. No caching —
 * every call hits the provider, which is what makes the listing memo in [DocumentPathResolver]
 * safe to keep.
 */
private class DocumentSnapshotQueries(private val contentResolver: ContentResolver) {
    fun queryPresence(documentUri: Uri, target: WorkspaceTarget, documentId: String): DocumentPresence = try {
        val snapshot = querySnapshot(documentUri, target, documentId, SnapshotDigestMode.METADATA_ONLY)
        if (snapshot == null) DocumentPresence.Missing else DocumentPresence.Present(snapshot)
    } catch (_: FileNotFoundException) {
        DocumentPresence.Missing
    }

    fun queryDisplayName(documentUri: Uri): String? {
        contentResolver
            .query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                return cursor.getString(0)
            }
        return null
    }

    fun querySnapshot(
        documentUri: Uri,
        target: WorkspaceTarget,
        documentId: String,
        digestMode: SnapshotDigestMode,
    ): PlatformDocumentSnapshot? {
        contentResolver
            .query(documentUri, ContentResolverPlatformDocumentsGateway.DOCUMENT_PROJECTION, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                return snapshotFromCursor(cursor, target, documentId, documentUri, digestMode)
            }
        return null
    }

    private fun snapshotFromCursor(
        cursor: Cursor,
        target: WorkspaceTarget,
        documentId: String,
        documentUri: Uri,
        digestMode: SnapshotDigestMode,
    ): PlatformDocumentSnapshot {
        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val mime = cursor.getString(mimeIndex)
        val kind =
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                DocumentKind.DIRECTORY
            } else {
                DocumentKind.FILE
            }
        val length =
            if (kind == DocumentKind.DIRECTORY) {
                0uL
            } else {
                cursor.getLong(sizeIndex).coerceAtLeast(0L).toULong()
            }
        val lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0L)
        val digest =
            if (kind == DocumentKind.FILE && digestMode == SnapshotDigestMode.CONTENT) {
                digestDocument(documentUri)
            } else {
                null
            }
        return PlatformDocumentSnapshot(
            target = target,
            kind = kind,
            mimeType = mime?.takeUnless { it == DocumentsContract.Document.MIME_TYPE_DIR },
            length = length,
            lastModifiedEpochMillis = lastModified,
            documentId = documentId,
            digest = digest,
            displayName = cursor.getString(nameIndex),
        )
    }

    private fun digestDocument(documentUri: Uri): String? =
        contentResolver.openInputStream(documentUri)?.use { input -> input.sha256Hex() }
}

/**
 * Create/write/move mechanics for SAF trees. Every mutation the gateway commits is recorded in
 * [DocumentPathResolver]'s per-parent listing memo, so later name resolutions keep answering
 * from verified live data instead of re-listing the provider.
 */
private class DocumentWriter(
    private val contentResolver: ContentResolver,
    private val paths: DocumentPathResolver,
    private val queries: DocumentSnapshotQueries,
    /** Per-tree verdict for providers without dual-mode (rwt) file descriptors. */
    private val twoPassTrees: MutableSet<String>,
    private val boundToProvider: Boolean,
) {
    private fun String.toProviderTreeUri(): Uri =
        if (boundToProvider) stripContentUriUserId(this).toAndroidUri() else toAndroidUri()

    fun ensureDirectory(treeUri: String, path: String): PlatformDocumentSnapshot {
        val root = treeUri.toProviderTreeUri()
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty()) { "directory path must not be empty" }
        var parentDocId = selectedSafDocumentId(treeUri, root)
        for (segment in segments) {
            val existing = paths.findChild(root, treeUri, parentDocId, segment, bypassListings = true)
            if (existing != null) {
                parentDocId = existing.documentId
                continue
            }
            val created =
                createChildDocument(
                    root,
                    treeUri,
                    parentDocId,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment,
                )
            parentDocId = DocumentsContract.getDocumentId(created)
        }
        // The created/existing leaf document id is already known: observe it directly instead of
        // re-walking every path segment a second time, and stay metadata-only — a directory never
        // carries a content digest.
        return queries.querySnapshot(
            documentUri = DocumentsContract.buildDocumentUriUsingTree(root, parentDocId),
            target = WorkspaceTarget.Relative(path),
            documentId = parentDocId,
            digestMode = SnapshotDigestMode.METADATA_ONLY,
        ) ?: errorIo("Created directory is not observable: $path")
    }

    /**
     * One write law for every source: replacement observes the existing target live, while a
     * create allocates a new document and proves its exact name. Arbitration and allocation
     * share the resolved chain; the create anchors to the resolved
     * parent, the payload streams chunk-wise while hashing, the persisted document is verified
     * by digest, and a document this write created is rolled back when any step fails. Memory
     * stays bounded by [WRITE_CHUNK_BYTES]; [mode] `null` lets the observed presence decide
     * create-vs-replace.
     */
    fun writeToDocument(
        treeUri: String,
        path: String,
        source: InputStream,
        mode: WriteMode?,
        mimeType: String?,
        arbitrate: WriteArbitration,
    ): WriteOutcome {
        val root = treeUri.toProviderTreeUri()
        val chain = paths.resolveWriteChain(root, treeUri, path, mode)
        val existing =
            chain.leaf?.let { leaf ->
                queries.querySnapshot(
                    documentUri = leaf.uri,
                    target = WorkspaceTarget.Relative(path),
                    documentId = leaf.documentId,
                    digestMode = SnapshotDigestMode.CONTENT,
                )
            }
        arbitrate(existing)?.let { return WriteOutcome.Satisfied(it) }
        val effectiveMode = mode ?: if (chain.leaf == null) WriteMode.CREATE else WriteMode.REPLACE
        if (chain.leaf != null && effectiveMode == WriteMode.CREATE) {
            errorPostcondition("Create refused over existing path: $path")
        }
        val target = if (chain.leaf == null) {
            createInChain(root, treeUri, path, chain, mimeType ?: "application/octet-stream")
        } else {
            ExistingTarget(chain.leaf.uri)
        }
        var targetUri = target.uri
        val requestedName = path.substringAfterLast('/')
        try {
            val (persistedDigest, persistedLength) = writeAndVerify(treeUri, targetUri, source)
            var receipt =
                queries.querySnapshot(
                    documentUri = targetUri,
                    target = WorkspaceTarget.Relative(path),
                    documentId = DocumentsContract.getDocumentId(targetUri),
                    digestMode = SnapshotDigestMode.METADATA_ONLY,
                ) ?: errorIo("Written document is not observable: $path")
            // The receipt already selects the display name: a provider that sanitized the create
            // is repaired here, and only the receipt-verified name enters the listing memo.
            if (target is CreatedTarget && receipt.displayName == null) {
                errorIo("Created document has no display name: $path")
            }
            if (target is CreatedTarget && receipt.displayName != requestedName) {
                targetUri =
                    repairDisplayName(
                        treeUri = treeUri,
                        documentUri = targetUri,
                        parentDocId = target.parentDocId,
                        requestedName = requestedName,
                        currentName = receipt.displayName,
                        description = path,
                    )
                receipt =
                    queries.querySnapshot(
                        documentUri = targetUri,
                        target = WorkspaceTarget.Relative(path),
                        documentId = DocumentsContract.getDocumentId(targetUri),
                        digestMode = SnapshotDigestMode.METADATA_ONLY,
                    ) ?: errorIo("Renamed document is not observable: $path")
            }
            if (target is CreatedTarget) {
                paths.listings.noteChildAdded(
                    treeUri,
                    target.parentDocId,
                    requestedName,
                    receipt.documentId,
                )
            }
            return WriteOutcome.Written(
                receipt.copy(
                    digest = persistedDigest,
                    length = persistedLength.toULong(),
                ),
            )
        } catch (failure: Exception) {
            if (target is CreatedTarget) {
                rollbackCreated(treeUri, path, targetUri, target.parentDocId, requestedName, failure)
            }
            throw failure
        } finally {
            source.close()
        }
    }

    /**
     * A document this write created must not survive a failed write or verification: the
     * provider delete is best-effort and a rollback failure stays attached to the failure that
     * triggered it. The parent's listing memo loses the rolled-back name in the same step.
     */
    private fun rollbackCreated(
        treeUri: String,
        path: String,
        targetUri: Uri,
        parentDocId: String,
        requestedName: String,
        failure: Exception,
    ) {
        try {
            if (!DocumentsContract.deleteDocument(contentResolver, targetUri)) {
                failure.addSuppressed(
                    IOException("Incomplete created document could not be deleted: $path"),
                )
            } else {
                paths.listings.noteChildRemoved(
                    treeUri,
                    parentDocId,
                    requestedName,
                    DocumentsContract.getDocumentId(targetUri),
                )
            }
        } catch (rollbackFailure: Exception) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    fun move(treeUri: String, source: String, target: String, targetTreeUri: String): PlatformDocumentSnapshot {
        if (boundToProvider &&
            treeUri != targetTreeUri &&
            parseContentUriAuthority(treeUri) != parseContentUriAuthority(targetTreeUri)
        ) {
            errorIo("Cannot move documents across different document providers or user profiles")
        }
        val root = treeUri.toProviderTreeUri()
        val targetRoot = targetTreeUri.toProviderTreeUri()
        val sourceResolved =
            paths.resolvePath(root, treeUri, source, bypassListings = true)
                ?: errorNotFound("Missing source: $source")
        val targetParentPath = target.substringBeforeLast('/', missingDelimiterValue = "")
        val targetName = target.substringAfterLast('/')
        val parentDocId =
            if (targetParentPath.isEmpty()) {
                selectedSafDocumentId(targetTreeUri, targetRoot)
            } else {
                ensureDirectory(targetTreeUri, targetParentPath).documentId
            }
        val sourceParentDocId =
            source.substringBeforeLast('/', missingDelimiterValue = "").let { parentPath ->
                if (parentPath.isEmpty()) {
                    selectedSafDocumentId(treeUri, root)
                } else {
                    paths.resolvePath(root, treeUri, parentPath, bypassListings = true)?.documentId
                        ?: errorNotFound("Missing source parent: $parentPath")
                }
            }
        val targetExisting = paths.resolvePath(targetRoot, targetTreeUri, target, bypassListings = true)
        if (targetExisting != null && targetExisting.uri != sourceResolved.uri) {
            if (DocumentsContract.deleteDocument(contentResolver, targetExisting.uri)) {
                paths.listings.noteChildRemoved(targetTreeUri, parentDocId, targetName, targetExisting.documentId)
            }
        }
        val targetDocUri =
            if (sourceParentDocId == parentDocId) {
                sourceResolved.uri
            } else {
                DocumentsContract.moveDocument(
                    contentResolver,
                    sourceResolved.uri,
                    DocumentsContract.buildDocumentUriUsingTree(root, sourceParentDocId),
                    DocumentsContract.buildDocumentUriUsingTree(targetRoot, parentDocId),
                )?.also { movedUri ->
                    paths.listings.noteChildRemoved(
                        treeUri,
                        sourceParentDocId,
                        source.substringAfterLast('/'),
                        sourceResolved.documentId,
                    )
                    paths.listings.noteChildAdded(
                        targetTreeUri,
                        parentDocId,
                        source.substringAfterLast('/'),
                        DocumentsContract.getDocumentId(movedUri),
                    )
                } ?: errorIo("moveDocument returned null for $source -> $target")
            }
        val finalUri =
            repairDisplayName(
                treeUri = targetTreeUri,
                documentUri = targetDocUri,
                parentDocId = parentDocId,
                requestedName = targetName,
                currentName = null,
                description = target,
            )
        // The moved document's identity is the URI the provider just returned — observe it
        // directly (metadata-only) instead of re-resolving the target path through every
        // intermediate findChild query a second time.
        return queries.querySnapshot(
            documentUri = finalUri,
            target = WorkspaceTarget.Relative(target),
            documentId = DocumentsContract.getDocumentId(finalUri),
            digestMode = SnapshotDigestMode.METADATA_ONLY,
        ) ?: errorIo("Moved document is not observable: $target")
    }

    private fun repairDisplayName(
        treeUri: String,
        documentUri: Uri,
        parentDocId: String,
        requestedName: String,
        currentName: String?,
        description: String,
    ): Uri {
        val root = treeUri.toProviderTreeUri()
        val initialName = currentName ?: queries.queryDisplayName(documentUri)
        if (initialName == null || initialName == requestedName) return documentUri
        val existing = paths.findChild(root, treeUri, parentDocId, requestedName, bypassListings = true)
        if (existing != null) {
            errorPostcondition("Cannot repair display name: $requestedName is occupied")
        }
        val renamed =
            DocumentsContract.renameDocument(contentResolver, documentUri, requestedName)
                ?: errorIo("rename failed for $description")
        paths.listings.noteChildMoved(
            treeUri,
            ListingChild(parentDocId, initialName, DocumentsContract.getDocumentId(documentUri)),
            ListingChild(parentDocId, requestedName, DocumentsContract.getDocumentId(renamed)),
        )
        val finalName = queries.queryDisplayName(renamed)
        if (finalName != requestedName) {
            errorIo("rename did not honor requested display name for $description: got $finalName")
        }
        return renamed
    }

    /**
     * Materialize the write target under the walk-resolved parent: create each missing directory
     * segment (recording each in the parent's listing memo), then the leaf file. The leaf create
     * is only memoized after the write receipt proves the provider-observed name.
     */
    private fun createInChain(
        root: Uri,
        treeUri: String,
        path: String,
        chain: WriteChain,
        mimeType: String,
    ): CreatedTarget {
        var parentDocId = chain.parentDocId
        for (segment in chain.missingDirs) {
            parentDocId =
                DocumentsContract.getDocumentId(
                    createChildDocument(root, treeUri, parentDocId, DocumentsContract.Document.MIME_TYPE_DIR, segment),
                )
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(root, parentDocId)
        val uri =
            DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                mimeType,
                path.substringAfterLast('/'),
            ) ?: errorIo("Failed to create document $path")
        return CreatedTarget(uri = uri, parentDocId = parentDocId)
    }

    private fun createChildDocument(
        root: Uri,
        treeUri: String,
        parentDocId: String,
        mimeType: String,
        name: String,
    ): Uri {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(root, parentDocId)
        return DocumentsContract.createDocument(contentResolver, parentUri, mimeType, name)
            ?.also { created ->
                paths.listings.noteChildAdded(treeUri, parentDocId, name, DocumentsContract.getDocumentId(created))
            }
            ?: errorIo("Failed to create document $name")
    }

    private sealed interface WriteTarget {
        val uri: Uri
    }

    private data class ExistingTarget(override val uri: Uri) : WriteTarget

    private data class CreatedTarget(override val uri: Uri, val parentDocId: String) : WriteTarget

    /**
     * Persist and prove the landed bytes. Providers with dual-mode descriptors take the
     * single-descriptor path: write, sync and readback verification share one open, so the
     * receipt attests durable bytes a crash cannot split from the fsync. Providers rejecting
     * `rwt` degrade per-tree to the two-pass stream pair.
     */
    private fun writeAndVerify(treeUri: String, targetUri: Uri, source: InputStream): Pair<String, Long> =
        if (treeUri in twoPassTrees) {
            writeTwoPassAndVerify(targetUri, source)
        } else {
            try {
                writeSyncAndVerify(targetUri, source)
            } catch (unsupported: java.io.FileNotFoundException) {
                // behavior-contract: silent-result-ok: a provider without dual-mode
                // descriptors answers openFileDescriptor with FileNotFoundException; the tree
                // then takes the two-pass stream pair for this and later writes.
                Timber.d(unsupported, "provider declined rwt descriptor for %s", treeUri)
                twoPassTrees += treeUri
                writeTwoPassAndVerify(targetUri, source)
            }
        }

    private fun writeSyncAndVerify(targetUri: Uri, source: InputStream): Pair<String, Long> {
        val pfd =
            contentResolver.openFileDescriptor(targetUri, "rwt")
                ?: throw java.io.FileNotFoundException("provider declined a read/write descriptor")
        try {
            val writtenDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            var total = 0L
            val output = java.io.FileOutputStream(pfd.fileDescriptor)
            val buffer = ByteArray(WRITE_CHUNK_BYTES)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                writtenDigest.update(buffer, 0, read)
                total += read
            }
            output.flush()
            try {
                pfd.fileDescriptor.sync()
            } catch (unsupported: java.io.SyncFailedException) {
                // behavior-contract: silent-result-ok: an unsyncable descriptor still verifies
                // by readback — durability matches the stream-close guarantee it replaces.
                Timber.d(unsupported, "provider descriptor refused sync")
            }
            val writtenDigestHex = writtenDigest.finishSha256Hex()
            val persisted =
                ParcelFileDescriptor.dup(pfd.fileDescriptor)?.let { dupFd ->
                    ParcelFileDescriptor.AutoCloseInputStream(dupFd).use { input ->
                        input.channel.position(0)
                        val readbackDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                        val readBuffer = ByteArray(WRITE_CHUNK_BYTES)
                        var persistedTotal = 0L
                        while (true) {
                            val read = input.read(readBuffer)
                            if (read < 0) break
                            readbackDigest.update(readBuffer, 0, read)
                            persistedTotal += read
                        }
                        if (persistedTotal != total || readbackDigest.finishSha256Hex() != writtenDigestHex) {
                            errorPostcondition("Written document readback does not match requested bytes")
                        }
                        persistedTotal
                    }
                } ?: errorIo("Written document cannot be reopened for verification")
            return writtenDigestHex to persisted
        } finally {
            pfd.close()
        }
    }

    private fun writeTwoPassAndVerify(targetUri: Uri, source: InputStream): Pair<String, Long> {
        val writtenDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            val buffer = ByteArray(WRITE_CHUNK_BYTES)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                writtenDigest.update(buffer, 0, read)
            }
        } ?: errorIo("openOutputStream returned null for $targetUri")
        val writtenDigestHex = writtenDigest.finishSha256Hex()
        val persisted =
            contentResolver.openInputStream(targetUri)?.use { input ->
                val readbackDigest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                val buffer = ByteArray(WRITE_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    readbackDigest.update(buffer, 0, read)
                    total += read
                }
                if (readbackDigest.finishSha256Hex() != writtenDigestHex) {
                    errorPostcondition("Written document readback does not match requested bytes")
                }
                total
            } ?: errorIo("Written document cannot be reopened for verification")
        return writtenDigestHex to persisted
    }

    private companion object {
        private const val DIGEST_ALGORITHM = "SHA-256"

        private const val WRITE_CHUNK_BYTES = 64 * 1024
    }
}

private data class DocumentColumnIndices(
    val idIndex: Int,
    val nameIndex: Int,
    val mimeIndex: Int,
    val sizeIndex: Int,
    val modifiedIndex: Int,
) {
    fun extractChildSnapshot(cursor: Cursor, target: WorkspaceTarget): PlatformDocumentSnapshot? {
        val name = cursor.getString(nameIndex) ?: return null
        val documentId = cursor.getString(idIndex) ?: return null
        val childPath =
            when (target) {
                is WorkspaceTarget.Root -> name
                is WorkspaceTarget.Relative -> "${target.path}/$name"
            }
        val mime = cursor.getString(mimeIndex)
        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val kind = if (isDir) DocumentKind.DIRECTORY else DocumentKind.FILE
        val length = if (isDir) 0uL else cursor.getLong(sizeIndex).coerceAtLeast(0L).toULong()
        val lastModified = cursor.getLong(modifiedIndex).coerceAtLeast(0L)
        return PlatformDocumentSnapshot(
            target = WorkspaceTarget.Relative(childPath),
            kind = kind,
            mimeType = mime?.takeUnless { isDir },
            length = length,
            lastModifiedEpochMillis = lastModified,
            documentId = documentId,
            digest = null,
            displayName = name,
        )
    }

    companion object {
        fun from(cursor: Cursor): DocumentColumnIndices = DocumentColumnIndices(
            idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE),
            sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE),
            modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
        )
    }
}

private fun errorNotFound(message: String): Nothing = throw PlatformActionAccessException(
    category = "storage",
    code = "document_not_found",
    diagnostic = message,
)

private fun incompleteDirectory(target: String) = PlatformActionAccessException(
    category = "storage",
    code = "metadata_enumeration_incomplete",
    diagnostic = "Directory could not be fully enumerated: $target",
    retryDisposition = "after_user_action",
)

private fun addDirectoryChild(names: MutableMap<String, String>, name: String, id: String, target: String) {
    if (names.putIfAbsent(name, id) != null) {
        throw PlatformActionAccessException(
            category = "conflict",
            code = "metadata_directory_name_conflict",
            diagnostic = "Directory contains multiple documents with the same name: $target",
        )
    }
}

private inline fun <T : Any> ContentResolver.withCompleteDirectory(
    uri: Uri,
    projection: Array<String>,
    target: String,
    observe: (Cursor) -> T,
): T = query(uri, projection, null, null, null)?.use { cursor ->
    val extras = cursor.extras
    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING)) {
        throw PlatformActionAccessException(
            category = "storage",
            code = "metadata_enumeration_loading",
            diagnostic = "Document provider is still listing: $target",
            retryDisposition = "transient",
        )
    }
    if (extras.containsKey(DocumentsContract.EXTRA_ERROR)) throw incompleteDirectory(target)
    observe(cursor)
} ?: throw incompleteDirectory(target)

private fun errorIo(message: String): Nothing = throw PlatformActionAccessException(
    category = "storage",
    code = "platform_io_error",
    diagnostic = message,
)

private fun errorPostcondition(message: String): Nothing = throw PlatformActionAccessException(
    category = "conflict",
    code = "platform_postcondition_mismatch",
    diagnostic = message,
)

private data class ListingKey(val treeUri: String, val parentDocId: String)

private sealed interface DocumentPresence {
    data class Present(val snapshot: PlatformDocumentSnapshot) : DocumentPresence
    data object Missing : DocumentPresence
}
