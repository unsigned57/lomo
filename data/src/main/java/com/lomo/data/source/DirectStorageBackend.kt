package com.lomo.data.source

import java.io.File

/**
 * Storage backend implementation using direct file system access.
 * Handles traditional file paths.
 */
class DirectStorageBackend(
    rootDir: File,
    secureWipeBeforeDeleteEnabled: suspend () -> Boolean = { false },
) : MarkdownStorageBackend by DirectMarkdownStorageBackendDelegate(rootDir, secureWipeBeforeDeleteEnabled),
    WorkspaceConfigBackend by DirectWorkspaceConfigBackendDelegate(rootDir),
    MediaStorageBackend by DirectMediaStorageBackendDelegate(rootDir)
