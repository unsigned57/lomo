package com.lomo.data.engine.media

/**
 * Host-testable workspace root resolver for media/archive path-only FFI.
 * Production uses the active Direct workspace location; SAF fails closed for path APIs.
 */
fun interface WorkspaceFilesystemRoot {
    /** Absolute filesystem path of the active Direct workspace, or null if unavailable. */
    fun absolutePathOrNull(): String?
}
