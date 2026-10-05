package com.lomo.data.engine

import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Descriptor-relative path resolution and description for a registered Direct grant root.
 *
 * Traversal descends through [SecureDirectoryStream]: every hop is opened `openat`-relative to an
 * already-verified parent descriptor, so the check and the use are the same descriptor operation.
 * A name-based `resolve` + `lstat` walk leaves a check-then-open window where a swapped component
 * redirects I/O outside the grant root; binding the whole walk to directory descriptors closes it.
 * A platform without descriptor-relative streams fails closed rather than degrading to name-based
 * traversal.
 */
internal class DirectRootPathAccess {
    /**
     * One verified in-root name plus the pinned descriptor of the directory containing it.
     *
     * [absolute] is the textual path for diagnostics and document identity only — it is never an
     * I/O target. Reads, writes, deletes and moves resolve [name] against [dir]'s descriptor, so
     * a path component swapped between check and use cannot escape the root.
     */
    class Resolved internal constructor(
        val absolute: Path,
        internal val dir: SecureDirectoryStream<Path>,
        internal val name: Path,
        internal val existed: Boolean,
    ) : AutoCloseable {
        override fun close() {
            dir.close()
        }

        /** lstat of [name] inside the pinned directory. */
        fun attributes(): BasicFileAttributes =
            dir
                .getFileAttributeView(
                    name,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ).readAttributes()

        /** Opens [name] inside the pinned directory without following links. */
        fun openChannel(vararg options: OpenOption): SeekableByteChannel =
            dir.newByteChannel(name, setOf(*options, LinkOption.NOFOLLOW_LINKS))

        /** Opens [name] — a verified directory — as its own pinned descriptor stream. */
        fun openDirectoryStream(): SecureDirectoryStream<Path> =
            dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)

        /** Deletes [name] from the pinned directory; a swapped component fails, never escapes. */
        fun delete() {
            if (attributes().isDirectory) {
                dir.deleteDirectory(name)
            } else {
                dir.deleteFile(name)
            }
        }

        /** Atomic rename of this name into [target]'s pinned directory under [target]'s name. */
        fun moveInto(target: Resolved) {
            dir.move(name, target.dir, target.name)
        }
    }

    /**
     * Resolves [relative] segment-by-segment beneath the grant root.
     *
     * Returns null when an intermediate segment is absent or not a directory. A symlink at any
     * segment is rejected before descent. A final segment that does not exist resolves with
     * [Resolved.existed] `false` so create/write callers can bind the verified parent.
     */
    fun resolveBeneath(
        grant: DirectCapabilityGrant,
        relative: String,
    ): Resolved? {
        val segments = relative.split('/')
        var stream = secureStream(grant.canonicalRoot.toPath())
        var absolute = grant.canonicalRoot.toPath()
        var resolved = false
        try {
            var result: Resolved? = null
            var index = 0
            while (index < segments.size) {
                val segment = segments[index]
                rejectEscapingSegment(segment)
                val name = Paths.get(segment)
                val isLast = index == segments.lastIndex
                var blocked = false
                val attributes =
                    try {
                        stream
                            .getFileAttributeView(
                                name,
                                BasicFileAttributeView::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).readAttributes()
                    } catch (ignored: NoSuchFileException) {
                        // behavior-contract: silent-result-ok: an absent component ends the walk;
                        // an absent final segment still resolves with existed=false below.
                        null
                    } catch (ignored: NotDirectoryException) {
                        // behavior-contract: silent-result-ok: a non-directory component means
                        // nothing can exist beneath this path; null is the not-found outcome.
                        blocked = true
                        null
                    }
                val descend =
                    when {
                        blocked || attributes == null -> {
                            if (!blocked && isLast) {
                                resolved = true
                                result =
                                    Resolved(
                                        absolute = absolute.resolve(segment),
                                        dir = stream,
                                        name = name,
                                        existed = false,
                                    )
                            }
                            false
                        }
                        attributes.isSymbolicLink -> rejectSymlink(segment)
                        isLast -> {
                            absolute = absolute.resolve(segment)
                            resolved = true
                            result = Resolved(absolute, stream, name, existed = true)
                            false
                        }
                        !attributes.isDirectory -> {
                            absolute = absolute.resolve(segment)
                            false
                        }
                        else -> {
                            absolute = absolute.resolve(segment)
                            val child =
                                try {
                                    stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
                                } catch (escaped: FileSystemException) {
                                    // The component was replaced between the lstat and the open;
                                    // the descriptor-relative open failed closed on the swap.
                                    rejectSymlink(segment, escaped)
                                }
                            stream.close()
                            stream = child
                            true
                        }
                    }
                if (!descend) break
                index++
            }
            return result
        } finally {
            if (!resolved) {
                try {
                    stream.close()
                } catch (_: IOException) {
                    // behavior-contract: silent-result-ok: descriptor release is best-effort;
                    // the resolution outcome is what propagates.
                }
            }
        }
    }

    /**
     * Resolves a write target: an existing target must be a regular file unless [mode] is
     * CREATE (which refuses any existing name); an absent target only needs a live parent.
     */
    fun requireFileTarget(
        grant: DirectCapabilityGrant,
        path: String,
        mode: WriteMode,
    ): Resolved {
        val resolved =
            resolveBeneath(grant, path)
                ?: throw DirectRootAccessException(
                    category = "storage",
                    code = "document_not_found",
                    diagnostic = "write parent directory is absent",
                )
        if (resolved.existed) {
            val regular =
                try {
                    resolved.attributes().isRegularFile
                } catch (failure: Exception) {
                    // The entry changed between resolve and stat; release the pinned parent
                    // descriptor before the lookup failure propagates.
                    resolved.close()
                    throw failure
                }
            val writable = mode != WriteMode.CREATE && regular
            if (!writable) {
                resolved.close()
                rejectUnwritableTarget(mode)
            }
        }
        return resolved
    }

    /** Resolves a move target whose parent must exist whether or not the name does. */
    fun requireAbsentOrFile(
        grant: DirectCapabilityGrant,
        path: String,
    ): Resolved =
        resolveBeneath(grant, path)
            ?: throw DirectRootAccessException(
                category = "storage",
                code = "document_not_found",
                diagnostic = "move parent directory is absent",
            )

    /**
     * Opens a descriptor-relative stream on [path]. The grant root itself is the only anchor
     * resolved by name, because grant registration already canonicalized it.
     */
    fun secureStream(path: Path): SecureDirectoryStream<Path> =
        Files.newDirectoryStream(path) as? SecureDirectoryStream<Path>
            ?: throw DirectRootAccessException(
                category = "permission",
                code = "descriptor_relative_unsupported",
                diagnostic = "Direct root requires descriptor-relative directory traversal",
            )

    fun snapshot(
        resolved: Resolved,
        target: WorkspaceTarget,
        bytes: ByteArray? = null,
    ): PlatformDocumentSnapshot =
        snapshot(
            absolute = resolved.absolute,
            attributes = resolved.attributes(),
            openReadChannel = { resolved.openChannel(StandardOpenOption.READ) },
            target = target,
            bytes = bytes,
        )

    /** Snapshot of a directory pinned open by [dir] — attributes come from the descriptor. */
    fun snapshotPinnedDirectory(
        dir: SecureDirectoryStream<Path>,
        absolute: Path,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot =
        snapshot(
            absolute = absolute,
            attributes = dir.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes(),
            openReadChannel = { Files.newByteChannel(absolute, StandardOpenOption.READ) },
            target = target,
        )

    /** Snapshot of the grant root anchor itself; its identity is established at registration. */
    fun snapshotAnchor(
        path: Path,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot =
        snapshot(
            absolute = path,
            attributes =
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS),
            openReadChannel = { Files.newByteChannel(path, StandardOpenOption.READ) },
            target = target,
        )

    /** Snapshot of one child inside an already-pinned directory stream. */
    fun snapshotChild(
        dir: SecureDirectoryStream<Path>,
        name: Path,
        attributes: BasicFileAttributes,
        absolute: Path,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot =
        snapshot(
            absolute = absolute,
            attributes = attributes,
            openReadChannel = {
                dir.newByteChannel(
                    name,
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                )
            },
            target = target,
        )

    /**
     * Writes [writer]'s bytes to a fresh sibling temp entry inside the target's pinned directory,
     * fsyncs it, then atomically renames it over the target name — all descriptor-relative.
     */
    fun writeAtomically(
        target: Resolved,
        writer: (FileChannel) -> Unit,
    ) {
        val tempName = Paths.get(".lomo-write-${UUID.randomUUID()}.tmp")
        val channel =
            target.dir.newByteChannel(
                tempName,
                setOf(
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )
        check(channel is FileChannel) {
            "Direct root requires channel-backed descriptor-relative writes"
        }
        var moved = false
        try {
            channel.use { output ->
                writer(output)
                output.force(true)
            }
            target.dir.move(tempName, target.dir, target.name)
            moved = true
        } finally {
            if (!moved) {
                try {
                    target.dir.deleteFile(tempName)
                } catch (ignored: IOException) {
                    // behavior-contract: silent-result-ok: orphaned temp names are best-effort
                    // cleanup; the original failure is what propagates.
                }
            }
        }
    }

    private fun snapshot(
        absolute: Path,
        attributes: BasicFileAttributes,
        openReadChannel: () -> SeekableByteChannel,
        target: WorkspaceTarget,
        bytes: ByteArray? = null,
    ): PlatformDocumentSnapshot {
        val directory = attributes.isDirectory
        // A supplied array digests in place; an unsupplied file digests by stream so
        // observation never buffers whole documents into memory.
        val digest =
            when {
                directory -> null
                bytes != null -> bytes.sha256Hex()
                else -> openReadChannel().use { channel -> Channels.newInputStream(channel).sha256Hex() }
            }
        return PlatformDocumentSnapshot(
            target = target,
            kind = if (directory) DocumentKind.DIRECTORY else DocumentKind.FILE,
            mimeType =
                when {
                    directory -> null
                    absolute.fileName.toString().endsWith(".md", ignoreCase = true) -> "text/markdown"
                    else -> "application/octet-stream"
                },
            length =
                when {
                    directory -> 0uL
                    bytes != null -> bytes.size.toULong()
                    else -> attributes.size().toULong()
                },
            lastModifiedEpochMillis = attributes.lastModifiedTime().toMillis().coerceAtLeast(0L),
            documentId = documentId(absolute, attributes),
            digest = digest,
        )
    }

    private fun documentId(
        path: Path,
        attributes: BasicFileAttributes,
    ): String {
        val key = attributes.fileKey()?.toString()
        return key ?: path.toAbsolutePath().toString()
    }
}

internal class DirectRootAccessException(
    val category: String,
    val code: String,
    val diagnostic: String,
    cause: Throwable? = null,
) : RuntimeException("$code: $diagnostic", cause)

internal fun rejectEscapingSegment(segment: String) {
    if (segment.isEmpty() || segment == "." || segment == "..") {
        throw DirectRootAccessException(
            category = "permission",
            code = "symlink_escape_rejected",
            diagnostic = "invalid relative path segment '$segment'",
        )
    }
}

internal fun rejectUnwritableTarget(mode: WriteMode): Nothing =
    throw DirectRootAccessException(
        category = "conflict",
        code = "platform_postcondition_mismatch",
        diagnostic =
            if (mode == WriteMode.CREATE) {
                "Create refused because the target already exists"
            } else {
                "write target is not a regular file"
            },
    )

internal fun rejectSymlink(
    segment: String,
    cause: Throwable? = null,
): Nothing =
    throw DirectRootAccessException(
        category = "permission",
        code = "symlink_escape_rejected",
        diagnostic = "symbolic link traversal rejected for '$segment'",
        cause = cause,
    )
