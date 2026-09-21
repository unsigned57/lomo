package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.streams.toList

/**
 * File-root document operations bound to a registered Direct grant.
 *
 * Paths are walked one segment at a time with [LinkOption.NOFOLLOW_LINKS]; [DirectRootPathAccess]
 * owns that resolution. A concatenated canonicalize plus `startsWith(root)` is not treated as a
 * capability.
 *
 * Every operation is reachable only from the platform batch executor and the native job driver,
 * which block in a bounded poll loop by design; no path here runs on the main thread.
 */
internal class DirectRootDocumentsGateway {
    private val access = DirectRootPathAccess()

    fun stat(
        grant: DirectCapabilityGrant,
        target: WorkspaceTarget,
    ): PlatformDocumentSnapshot? {
        val path =
            when (target) {
                is WorkspaceTarget.Root -> grant.canonicalRoot.toPath()
                is WorkspaceTarget.Relative -> access.openBeneath(grant, target.path) ?: return null
            }
        return access.snapshot(path, target)
    }

    fun listChildren(
        grant: DirectCapabilityGrant,
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage {
        val directory =
            when (target) {
                is WorkspaceTarget.Root -> grant.canonicalRoot.toPath()
                is WorkspaceTarget.Relative ->
                    access.openBeneath(grant, target.path) ?: return incompletePage()
            }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return incompletePage()
        }
        val children =
            try {
                Files.list(directory).use { stream ->
                    stream.sorted().toList()
                }
            } catch (_: IOException) {
                return incompletePage()
            } catch (_: SecurityException) {
                return incompletePage()
            }
        if (children.any { child -> Files.isSymbolicLink(child) }) {
            return incompletePage()
        }
        val skip = cursor?.toIntOrNull() ?: 0
        if (skip < 0 || skip > children.size) {
            return incompletePage()
        }
        val page = children.drop(skip).take(pageSize.toInt())
        val items =
            page.map { child ->
                val relative =
                    when (target) {
                        is WorkspaceTarget.Root -> child.fileName.toString()
                        is WorkspaceTarget.Relative -> "${target.path}/${child.fileName}"
                    }
                access.snapshot(child, WorkspaceTarget.Relative(relative))
            }
        val nextCursor =
            if (skip + page.size < children.size) {
                (skip + page.size).toString()
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
        var current = grant.canonicalRoot.toPath()
        for (segment in segments) {
            rejectEscapingSegment(segment)
            val next = current.resolve(segment)
            if (Files.isSymbolicLink(next)) {
                throw symlinkRejected(segment)
            }
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(next)
                } catch (_: FileAlreadyExistsException) {
                    // behavior-contract: silent-result-ok: a concurrent writer created the entry;
                    // the NOFOLLOW directory postcondition below decides the outcome.
                }
            }
            if (!Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                throw DirectRootAccessException(
                    category = "conflict",
                    code = "platform_postcondition_mismatch",
                    diagnostic = "directory create collided with a non-directory",
                )
            }
            current = next
        }
        return access.snapshot(current, WorkspaceTarget.Relative(path))
    }

    fun openRead(
        grant: DirectCapabilityGrant,
        path: String,
    ): PlatformReadHandle {
        val file =
            access.openBeneath(grant, path)
                ?: throw DirectRootAccessException(
                    category = "storage",
                    code = "document_not_found",
                    diagnostic = "Target document is absent",
                )
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw DirectRootAccessException(
                category = "validation",
                code = "document_not_file",
                diagnostic = "ReadToExchange requires a file target",
            )
        }
        val bytes = Files.readAllBytes(file)
        return PlatformReadHandle(
            snapshot = access.snapshot(file, WorkspaceTarget.Relative(path), bytes),
            bytes = bytes,
        )
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
    ): PlatformDocumentSnapshot {
        val target = access.requireFileTarget(grant, path, mode)
        val parent = target.parent ?: grant.canonicalRoot.toPath()
        // behavior-contract: blocking-io-ok: runs on the platform batch executor / native job driver
        val temporary = Files.createTempFile(parent, ".lomo-write-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            replaceAtomically(temporary, target)
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw error
        }
        return access
            .snapshot(target, WorkspaceTarget.Relative(path), bytes)
            .copy(mimeType = mimeType)
    }

    fun writeFromFile(
        grant: DirectCapabilityGrant,
        path: String,
        source: File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot {
        val target = access.requireFileTarget(grant, path, mode)
        val parent = target.parent ?: grant.canonicalRoot.toPath()
        // behavior-contract: blocking-io-ok: runs on the platform batch executor / native job driver
        val temporary = Files.createTempFile(parent, ".lomo-write-", ".tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary.toFile()).use { output ->
                    val buffer = ByteArray(WRITE_CHUNK_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            replaceAtomically(temporary, target)
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw error
        }
        return access.snapshot(target, WorkspaceTarget.Relative(path)).copy(mimeType = mimeType)
    }

    fun move(
        grant: DirectCapabilityGrant,
        source: String,
        target: String,
    ): PlatformDocumentSnapshot {
        val from =
            access.openBeneath(grant, source)
                ?: throw DirectRootAccessException(
                    category = "storage",
                    code = "document_not_found",
                    diagnostic = "Move source is absent",
                )
        val to = access.requireAbsentOrFile(grant, target)
        replaceAtomically(from, to)
        return access.snapshot(to, WorkspaceTarget.Relative(target))
    }

    fun delete(
        grant: DirectCapabilityGrant,
        path: String,
    ) {
        val target =
            access.openBeneath(grant, path)
                ?: return
        try {
            Files.delete(target)
        } catch (_: DirectoryNotEmptyException) {
            throw DirectRootAccessException(
                category = "conflict",
                code = "platform_postcondition_mismatch",
                diagnostic = "directory delete requires an empty directory",
            )
        }
    }

    private fun incompletePage(): PlatformMetadataPage =
        PlatformMetadataPage(items = emptyList(), nextCursor = null, incomplete = true)

    private companion object {
        const val WRITE_CHUNK_BYTES = 64 * 1024
    }
}
