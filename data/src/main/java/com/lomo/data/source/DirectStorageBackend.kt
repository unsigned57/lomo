package com.lomo.data.source

import java.io.File

/**
 * Storage backend implementation using direct file system access.
 * Handles traditional file paths.
 */
class DirectStorageBackend(
    rootDir: File,
) : MarkdownStorageBackend by DirectMarkdownStorageBackendDelegate(rootDir),
    WorkspaceConfigBackend by DirectWorkspaceConfigBackendDelegate(rootDir),
    MediaStorageBackend by DirectMediaStorageBackendDelegate(rootDir)
