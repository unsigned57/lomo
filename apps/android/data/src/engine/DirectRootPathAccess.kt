package com.lomo.data.engine

import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Segment-wise path resolution and description for a registered Direct grant root.
 *
 * Every walk uses [LinkOption.NOFOLLOW_LINKS]. That is the same fallback Linux uses when `openat2`
 * is unavailable: a concatenated canonicalize plus `startsWith(root)` is not treated as a
 * capability.
 */
internal class DirectRootPathAccess {
    fun openBeneath(
        grant: DirectCapabilityGrant,
        relative: String,
    ): Path? {
        var current = grant.canonicalRoot.toPath()
        for (segment in relative.split('/')) {
            rejectEscapingSegment(segment)
            val next = current.resolve(segment)
            if (Files.isSymbolicLink(next)) {
                throw symlinkRejected(segment)
            }
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                return null
            }
            current = next
        }
        return current
    }

    fun requireFileTarget(
        grant: DirectCapabilityGrant,
        path: String,
        mode: WriteMode,
    ): Path {
        val existing = openBeneath(grant, path)
        if (existing != null) {
            val writable =
                mode != WriteMode.CREATE && Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)
            if (!writable) {
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
            }
            return existing
        }
        return resolveAbsentTarget(grant, path, "write parent directory is absent")
    }

    fun requireAbsentOrFile(
        grant: DirectCapabilityGrant,
        path: String,
    ): Path {
        openBeneath(grant, path)?.let { existing -> return existing }
        return resolveAbsentTarget(grant, path, "move parent directory is absent")
    }

    fun snapshot(
        path: Path,
        target: WorkspaceTarget,
        bytes: ByteArray? = null,
    ): PlatformDocumentSnapshot {
        val attributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val directory = attributes.isDirectory
        // A supplied array digests in place; an unsupplied file digests by stream so
        // observation never buffers whole documents into memory.
        val digest =
            when {
                directory -> null
                bytes != null -> bytes.sha256Hex()
                else -> Files.newInputStream(path).use { input -> input.sha256Hex() }
            }
        return PlatformDocumentSnapshot(
            target = target,
            kind = if (directory) DocumentKind.DIRECTORY else DocumentKind.FILE,
            mimeType =
                when {
                    directory -> null
                    path.fileName.toString().endsWith(".md", ignoreCase = true) -> "text/markdown"
                    else -> "application/octet-stream"
                },
            length =
                when {
                    directory -> 0uL
                    bytes != null -> bytes.size.toULong()
                    else -> attributes.size().toULong()
                },
            lastModifiedEpochMillis = attributes.lastModifiedTime().toMillis().coerceAtLeast(0L),
            documentId = documentId(path, attributes),
            digest = digest,
        )
    }

    private fun resolveAbsentTarget(
        grant: DirectCapabilityGrant,
        path: String,
        missingParentDiagnostic: String,
    ): Path {
        val parentRelative = path.substringBeforeLast('/', missingDelimiterValue = "")
        val parent =
            if (parentRelative.isEmpty()) {
                grant.canonicalRoot.toPath()
            } else {
                openBeneath(grant, parentRelative)
                    ?: throw DirectRootAccessException(
                        category = "storage",
                        code = "document_not_found",
                        diagnostic = missingParentDiagnostic,
                    )
            }
        val name = path.substringAfterLast('/')
        rejectEscapingSegment(name)
        return parent.resolve(name)
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
) : RuntimeException("$code: $diagnostic")

internal fun rejectEscapingSegment(segment: String) {
    if (segment.isEmpty() || segment == "." || segment == "..") {
        throw DirectRootAccessException(
            category = "permission",
            code = "symlink_escape_rejected",
            diagnostic = "invalid relative path segment '$segment'",
        )
    }
}

internal fun symlinkRejected(segment: String): DirectRootAccessException =
    DirectRootAccessException(
        category = "permission",
        code = "symlink_escape_rejected",
        diagnostic = "symbolic link traversal rejected for '$segment'",
    )

internal fun replaceAtomically(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        throw DirectRootAccessException(
            category = "permission",
            code = "atomic_replace_unsupported",
            diagnostic = "Direct root cannot atomically replace the target document",
        )
    }
}
