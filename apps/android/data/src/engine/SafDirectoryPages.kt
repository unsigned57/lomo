package com.lomo.data.engine

import com.lomo.nativebridge.WorkspaceTarget
import java.util.UUID

/**
 * SAF providers need not support limit/offset queries. Materialize their metadata cursor once,
 * close it with its provider lease, and page that immutable observation across the Rust boundary.
 * No provider cursor or connection survives a call. Only unfinished snapshots occupy the FIFO
 * budget; eviction is an explicit stale cursor, never a partial/empty successful enumeration.
 */
internal class SafDirectoryPages {
    private data class Snapshot(
        val tree: String,
        val target: WorkspaceTarget,
        val rows: List<PlatformDocumentSnapshot>,
        val bytes: Long,
    )

    private val snapshots = LinkedHashMap<String, Snapshot>()
    private var retainedBytes = 0L

    fun capture(rows: Sequence<PlatformDocumentSnapshot>): List<PlatformDocumentSnapshot> {
        var bytes = 0L
        return rows.onEach { row ->
            bytes += row.estimatedBytes()
            if (bytes > MAX_SNAPSHOT_BYTES) {
                throw PlatformActionAccessException(
                    category = "resource_limit",
                    code = "directory_snapshot_too_large",
                    diagnostic = "SAF directory metadata exceeds the $MAX_SNAPSHOT_BYTES byte snapshot budget",
                )
            }
        }.toList()
    }

    @Synchronized
    fun first(
        tree: String,
        target: WorkspaceTarget,
        rows: List<PlatformDocumentSnapshot>,
        pageSize: UInt,
    ): PlatformMetadataPage {
        val count = requirePageSize(pageSize)
        if (rows.size <= count) return PlatformMetadataPage(rows, null)
        val snapshot = Snapshot(tree, target, rows, rows.sumOf { it.estimatedBytes() })
        while (retainedBytes + snapshot.bytes > MAX_RETAINED_BYTES || snapshots.size >= MAX_SNAPSHOTS) {
            remove(snapshots.keys.first())
        }
        // behavior-contract: identity-mint-ok: this adapter owns ephemeral listing cursors, not durable memo identity
        val id = UUID.randomUUID().toString()
        snapshots[id] = snapshot
        retainedBytes += snapshot.bytes
        return PlatformMetadataPage(rows.take(count), "s1.$id.$count")
    }

    @Synchronized
    fun resume(tree: String, target: WorkspaceTarget, cursor: String, pageSize: UInt): PlatformMetadataPage {
        val count = requirePageSize(pageSize)
        val (id, offset) = decode(cursor)
        val snapshot = snapshots[id] ?: throw PlatformActionAccessException(
            category = "conflict",
            code = "stale_directory_cursor",
            diagnostic = "SAF listing snapshot has finished or was evicted; restart enumeration",
        )
        if (snapshot.tree != tree || snapshot.target != target || offset !in 1 until snapshot.rows.size) {
            throw invalidCursor()
        }
        val end = (offset.toLong() + count.toLong()).coerceAtMost(snapshot.rows.size.toLong()).toInt()
        val page = snapshot.rows.subList(offset, end).toList()
        val next = if (end < snapshot.rows.size) "s1.$id.$end" else null
        if (next == null) remove(id)
        return PlatformMetadataPage(page, next)
    }

    private fun remove(id: String) {
        val removed = checkNotNull(snapshots.remove(id))
        retainedBytes -= removed.bytes
    }

    private fun PlatformDocumentSnapshot.estimatedBytes(): Long {
        val path = when (val location = target) {
            is WorkspaceTarget.Root -> ""
            is WorkspaceTarget.Relative -> location.path
        }
        val textLength = path.length.toLong() + documentId.length + (displayName?.length ?: 0) + (mimeType?.length ?: 0)
        return ROW_ESTIMATED_BYTES + 2L * textLength
    }

    private fun decode(cursor: String): Pair<String, Int> {
        if (!CURSOR.matches(cursor)) throw invalidCursor()
        val parts = cursor.split('.')
        val offset = parts[2].toIntOrNull() ?: throw invalidCursor()
        return parts[1] to offset
    }

    private fun invalidCursor() = PlatformActionAccessException(
        category = "validation",
        code = "invalid_page_cursor",
        diagnostic = "SAF page cursor must belong to this tree, directory and retained snapshot",
    )

    companion object {
        private const val MAX_PAGE_SIZE = 512u
        private const val ROW_ESTIMATED_BYTES = 128L

        fun requirePageSize(value: UInt): Int {
            if (value !in 1u..MAX_PAGE_SIZE) {
                throw PlatformActionAccessException(
                    category = "resource_limit",
                    code = "invalid_page_size",
                    diagnostic = "page size must be within 1..=$MAX_PAGE_SIZE",
                )
            }
            return value.toInt()
        }

        private const val MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024
        private const val MAX_RETAINED_BYTES = 64L * 1024 * 1024
        private const val MAX_SNAPSHOTS = 16
        private val CURSOR = Regex("s1\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[0-9]+")
    }
}
