package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/**
 * File-root document operations bound to a registered Direct grant.
 *
 * Every resolution and terminal use is descriptor-relative: [DirectRootPathAccess] descends
 * through [SecureDirectoryStream] so the inode verified is the inode opened. A concatenated
 * canonicalize plus `startsWith(root)` — or a name-based check-then-open — is not a capability.
 *
 * Every operation is reachable only from the platform batch executor and the native job driver,
 * which block in a bounded poll loop by design; no path here runs on the main thread.
 */
internal class DirectRootDocumentsGateway {
    private val access = DirectRootPathAccess()

    fun stat(
        grant: DirectCapabilityGrant,
        target: WorkspaceTarget,
        digest: SnapshotDigestMode,
    ): PlatformDocumentSnapshot? =
        when (target) {
            is WorkspaceTarget.Root ->
                access.snapshotAnchor(grant.canonicalRoot.toPath(), target)
            is WorkspaceTarget.Relative ->
                access
                    .resolveBeneath(grant, target.path)
                    ?.use { resolved ->
                        if (!resolved.existed) {
                            null
                        } else {
                            access.snapshot(resolved, target, digest = digest)
                        }
                    }
        }

    fun listChildren(
        grant: DirectCapabilityGrant,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage =
        when (target) {
            is WorkspaceTarget.Root ->
                access.secureStream(grant.canonicalRoot.toPath()).use { stream ->
                    listChildrenIn(
                        stream,
                        grant.canonicalRoot.toPath(),
                        "",
                        grant.stableWorkspaceId.value,
                        cursor,
                        pageSize,
                    )
                }
            is WorkspaceTarget.Relative ->
                access
                    .resolveBeneath(grant, target.path)
                    ?.use { resolved ->
                        if (!resolved.existed) return incompletePage()
                        val stream =
                            try {
                                resolved.openDirectoryStream()
                            } catch (ignored: IOException) {
                                // behavior-contract: silent-result-ok: an unlistable directory
                                // degrades to an incomplete page, never a crash
                                return incompletePage()
                            }
                        stream.use { childStream ->
                            listChildrenIn(
                                childStream,
                                resolved.absolute,
                                "${target.path}/",
                                grant.stableWorkspaceId.value,
                                cursor,
                                pageSize,
                            )
                        }
                    } ?: incompletePage()
        }

    /**
     * Keyset page over the pinned directory under the `name ASC` ordering.
     *
     * The directory stream yields `dir/name` entries, so the ordering key and the emitted target
     * path are built from each entry's bare [Path.getFileName]; stat stays descriptor-relative on
     * that bare name. Only the emitted page is stated — skipped and upcoming names cost no I/O —
     * and an emitted symlink still voids the whole listing at its own page (an entry that sorts
     * earlier simply proves its own page truthful).
     */
    private fun listChildrenIn(
        stream: SecureDirectoryStream<Path>,
        absolute: Path,
        relativePrefix: String,
        rootId: String,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage {
        require(pageSize > 0u) { "page size must be non-zero" }
        val sorted = enumerateSortedChildNames(stream) ?: return incompletePage()
        val fingerprint =
            DirectListPageCursor.membershipFingerprint(sorted.map { entry -> entry.toString() })
        val emitFrom =
            if (cursor == null) {
                0
            } else {
                val bound =
                    DirectListPageCursor.resolveBound(
                        raw = cursor,
                        rootId = rootId,
                        directory = relativePrefix,
                        membershipFingerprint = fingerprint,
                    )
                val boundIndex =
                    sorted.binarySearchBy(bound) { entry -> entry.toString() }
                if (boundIndex < 0) {
                    throw DirectRootAccessException(
                        category = "validation",
                        code = "invalid_page_cursor",
                        diagnostic =
                            "page cursor bound names a child absent from the fingerprinted set",
                    )
                }
                boundIndex + 1
            }
        val page = sorted.subList(emitFrom, minOf(emitFrom + pageSize.toInt(), sorted.size))
        val items =
            snapshotPageChildren(access, stream, absolute, relativePrefix, page)
                ?: return incompletePage()
        val nextCursor =
            if (emitFrom + page.size < sorted.size) {
                DirectListPageCursor(
                    rootId = rootId,
                    directory = relativePrefix,
                    lastName = page.last().toString(),
                    membershipFingerprint = fingerprint,
                ).encode()
            } else {
                null
            }
        return PlatformMetadataPage(items = items, nextCursor = nextCursor)
    }

    fun ensureDirectory(
        grant: DirectCapabilityGrant,
        path: String,
    ): PlatformDocumentSnapshot {
        val segments = path.split('/').filter(String::isNotEmpty)
        require(segments.isNotEmpty()) { "directory path must not be empty" }
        var stream = access.secureStream(grant.canonicalRoot.toPath())
        var absolute = grant.canonicalRoot.toPath()
        var completed = false
        try {
            segments.forEach { segment ->
                rejectEscapingSegment(segment)
                val name = java.nio.file.Paths.get(segment)
                absolute = absolute.resolve(segment)
                val attributes =
                    try {
                        stream
                            .getFileAttributeView(
                                name,
                                BasicFileAttributeView::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).readAttributes()
                    } catch (ignored: NoSuchFileException) {
                        // behavior-contract: silent-result-ok: an absent component means the
                        // entry must be created; null attributes select the create path below.
                        // Creation is the one operation without a descriptor-relative mkdir: the
                        // pinned re-open below binds whatever the name actually produced.
                        try {
                            Files.createDirectory(absolute)
                        } catch (_: FileAlreadyExistsException) {
                            // behavior-contract: silent-result-ok: a concurrent writer created the
                            // entry; the descriptor-relative re-open below decides the outcome.
                        }
                        null
                    }
                if (attributes != null) {
                    if (attributes.isSymbolicLink) rejectSymlink(segment)
                    if (!attributes.isDirectory) {
                        throw DirectRootAccessException(
                            category = "conflict",
                            code = "platform_postcondition_mismatch",
                            diagnostic = "directory create collided with a non-directory",
                        )
                    }
                }
                val child =
                    try {
                        stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
                    } catch (escaped: FileSystemException) {
                        rejectSymlink(segment, escaped)
                    }
                stream.close()
                stream = child
            }
            completed = true
        } finally {
            if (!completed) {
                try {
                    stream.close()
                } catch (_: IOException) {
                    // behavior-contract: silent-result-ok: descriptor release is best-effort; the
                    // original failure is what propagates.
                }
            }
        }
        return stream.use { pinned ->
            access.snapshotPinnedDirectory(pinned, absolute, WorkspaceTarget.Relative(path))
        }
    }

    fun openRead(
        grant: DirectCapabilityGrant,
        path: String,
    ): PlatformReadHandle {
        access.resolveBeneath(grant, path).use { target ->
            if (target == null || !target.existed) {
                throw DirectRootAccessException(
                    category = "storage",
                    code = "document_not_found",
                    diagnostic = "Target document is absent",
                )
            }
            if (!target.attributes().isRegularFile) {
                throw DirectRootAccessException(
                    category = "validation",
                    code = "document_not_file",
                    diagnostic = "ReadToExchange requires a file target",
                )
            }
            val bytes =
                target.openChannel().use { channel ->
                    // behavior-contract: full-load-ok: the read API hands the caller the whole
                    // document; direct-root documents are bounded by the provider's own size
                    Channels.newInputStream(channel).readBytes()
                }
            return PlatformReadHandle(
                snapshot =
                    access.snapshot(
                        target,
                        WorkspaceTarget.Relative(path),
                        bytes,
                        SnapshotDigestMode.CONTENT,
                    ),
                bytes = bytes,
            )
        }
    }

    fun openReadByHandle(
        grant: DirectCapabilityGrant,
        path: String,
        documentHandle: String,
    ): PlatformReadHandle {
        val handle = openRead(grant, path)
        if (handle.snapshot.documentId != documentHandle) {
            throw DirectRootAccessException(
                category = "conflict",
                code = "platform_postcondition_mismatch",
                diagnostic = "Direct document handle does not match the bound path",
            )
        }
        return handle
    }

    fun writeFromExchange(
        grant: DirectCapabilityGrant,
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot =
        access.requireFileTarget(grant, path, mode).use { target ->
            // behavior-contract: blocking-io-ok: runs on the platform batch executor / native job driver
            access.writeAtomically(target) { channel ->
                channel.writeFully(ByteBuffer.wrap(bytes))
            }
            access
                .snapshot(
                    target,
                    WorkspaceTarget.Relative(path),
                    bytes,
                    SnapshotDigestMode.CONTENT,
                ).copy(mimeType = mimeType)
        }

    fun writeFromFile(
        grant: DirectCapabilityGrant,
        path: String,
        source: File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot =
        access.requireFileTarget(grant, path, mode).use { target ->
            // behavior-contract: blocking-io-ok: runs on the platform batch executor / native job driver
            access.writeAtomically(target) { channel ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(WRITE_CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        channel.writeFully(ByteBuffer.wrap(buffer, 0, read))
                    }
                }
            }
            access
                // The persisted bytes are re-hashed from the descriptor so the receipt verifies
                // what actually landed, not merely what the stream was asked to write.
                .snapshot(
                    target,
                    WorkspaceTarget.Relative(path),
                    digest = SnapshotDigestMode.CONTENT,
                ).copy(mimeType = mimeType)
        }

    fun move(
        grant: DirectCapabilityGrant,
        source: String,
        target: String,
    ): PlatformDocumentSnapshot {
        access.resolveBeneath(grant, source).use { sourceResolved ->
            if (sourceResolved == null || !sourceResolved.existed) {
                throw DirectRootAccessException(
                    category = "storage",
                    code = "document_not_found",
                    diagnostic = "Move source is absent",
                )
            }
            access.requireAbsentOrFile(grant, target).use { targetResolved ->
                try {
                    sourceResolved.moveInto(targetResolved)
                } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                    throw DirectRootAccessException(
                        category = "permission",
                        code = "atomic_replace_unsupported",
                        diagnostic = "Direct root cannot atomically replace the target document",
                        cause = unsupported,
                    )
                }
                // The move receipt is path evidence; nothing downstream consumes a verified
                // digest here, so observing the landed document stays metadata-only instead of
                // re-hashing the whole moved file.
                return access.snapshot(
                    targetResolved,
                    WorkspaceTarget.Relative(target),
                    digest = SnapshotDigestMode.METADATA_ONLY,
                )
            }
        }
    }

    fun delete(
        grant: DirectCapabilityGrant,
        path: String,
    ) {
        access.resolveBeneath(grant, path).use { target ->
            if (target == null || !target.existed) return
            try {
                target.delete()
            } catch (_: DirectoryNotEmptyException) {
                throw DirectRootAccessException(
                    category = "conflict",
                    code = "platform_postcondition_mismatch",
                    diagnostic = "directory delete requires an empty directory",
                )
            }
        }
    }

    private fun incompletePage(): PlatformMetadataPage =
        PlatformMetadataPage(items = emptyList(), nextCursor = null, incomplete = true)

    private companion object {
        const val WRITE_CHUNK_BYTES = 64 * 1024
    }
}

/**
 * Bare-name enumeration backing `listChildrenIn`: the stream yields `dir/name` entries and the
 * keyset ordering compares each entry's [Path.getFileName] string. `null` marks an unlistable
 * directory — the call site degrades it to an incomplete page, never a crash.
 */
private fun enumerateSortedChildNames(
    stream: SecureDirectoryStream<Path>,
): List<Path>? =
    try {
        stream
            .map { entry -> entry.fileName }
            .sortedBy { entry -> entry.toString() }
    } catch (_: IOException) {
        // behavior-contract: silent-result-ok: an unlistable directory degrades to an
        // incomplete page at the call site, never a crash.
        null
    } catch (_: DirectoryIteratorException) {
        // behavior-contract: silent-result-ok: an unlistable directory degrades to an
        // incomplete page at the call site, never a crash.
        null
    } catch (_: SecurityException) {
        // behavior-contract: silent-result-ok: an unlistable directory degrades to an
        // incomplete page at the call site, never a crash.
        null
    }

/**
 * Stat + snapshot pass over the emitted keyset page; every read stays descriptor-relative on the
 * pinned stream's bare name. `null` voids the whole listing — a vanished name or an emitted
 * symlink means the observed set no longer matches what the cursor bound, so the call site emits
 * an incomplete page rather than a shorter "truthful" one.
 */
private fun snapshotPageChildren(
    access: DirectRootPathAccess,
    stream: SecureDirectoryStream<Path>,
    absolute: Path,
    relativePrefix: String,
    page: List<Path>,
): List<PlatformDocumentSnapshot>? =
    try {
        page.map { name ->
            val attributes =
                stream
                    .getFileAttributeView(
                        name,
                        BasicFileAttributeView::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    ).readAttributes()
            if (attributes.isSymbolicLink) return null
            access.snapshotChild(
                dir = stream,
                name = name,
                attributes = attributes,
                absolute = absolute.resolve(name),
                target = WorkspaceTarget.Relative("$relativePrefix$name"),
            )
        }
    } catch (_: NoSuchFileException) {
        // A name vanished between enumeration and stat: the set the cursor bound is no
        // longer what this page observed, so the listing is untrustworthy, not shorter.
        // behavior-contract: silent-result-ok: voids to an incomplete page at the call site.
        null
    } catch (_: IOException) {
        // behavior-contract: silent-result-ok: an unreadable child voids the listing to an
        // incomplete page at the call site, never a shorter one.
        null
    } catch (_: SecurityException) {
        // behavior-contract: silent-result-ok: an unreadable child voids the listing to an
        // incomplete page at the call site, never a shorter one.
        null
    }

private fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        write(buffer)
    }
}
