package com.lomo.data.sync

import com.lomo.data.util.runNonFatalCatching
import com.lomo.data.webdav.WebDavClient
import timber.log.Timber
import java.io.File

/**
 * One-time migration helpers that move files from the pre-layout structure
 * to the new directory layout.
 */
object SyncLayoutMigration {
    private const val LEGACY_IMAGE_FOLDER = "images"
    private const val LEGACY_VOICE_FOLDER = "voice"
    private const val LOMO_ROOT_FOLDER = "lomo"

    // ── WebDAV ──────────────────────────────────────────────────────────

    /**
     * Detects root-level `.md` files and old `images/`/`voice/` folders on the
     * WebDAV remote and moves them into `lomo/<folder>/` paths.
     *
     * Call **once** at the beginning of the first sync after upgrade.
     */
    fun migrateWebDavRemote(
        client: WebDavClient,
        layout: SyncDirectoryLayout,
    ) {
        val rootFiles =
            try {
                client.list("")
            } catch (_: Exception) {
                return
            }
        val legacyState = resolveLegacyWebDavState(rootFiles)
        if (!legacyState.needsMigration || legacyState.hasLomoRoot) return

        Timber.i("SyncLayoutMigration: migrating WebDAV remote to lomo/ layout")

        // Ensure target directories
        client.ensureDirectory(LOMO_ROOT_FOLDER)
        for (folder in layout.distinctFolders) {
            client.ensureDirectory("$LOMO_ROOT_FOLDER/$folder")
        }

        // Move memos
        for (memo in legacyState.oldMemos) {
            val filename = memo.path.trimStart('/')
            runNonFatalCatching {
                val content = client.get(filename)
                client.put(
                    path = "$LOMO_ROOT_FOLDER/${layout.memoFolder}/$filename",
                    bytes = content.bytes,
                    contentType = "text/markdown; charset=utf-8",
                    lastModifiedHint = content.lastModified,
                )
                client.delete(filename)
            }.onFailure { error ->
                Timber.w(error, "Failed to migrate WebDAV memo: %s", filename)
            }
        }

        // Move media folders
        migrateWebDavMediaFolder(client, LEGACY_IMAGE_FOLDER, "$LOMO_ROOT_FOLDER/${layout.imageFolder}")
        migrateWebDavMediaFolder(client, LEGACY_VOICE_FOLDER, "$LOMO_ROOT_FOLDER/${layout.voiceFolder}")
    }

    private fun migrateWebDavMediaFolder(
        client: WebDavClient,
        oldFolder: String,
        newFolder: String,
    ) {
        val files =
            try {
                client.list(oldFolder).filter { !it.isDirectory }
            } catch (_: Exception) {
                return
            }
        if (files.isEmpty()) return

        for (resource in files) {
            val filename = resource.path.substringAfterLast('/')
            runNonFatalCatching {
                val content = client.get(resource.path)
                client.put(
                    path = "$newFolder/$filename",
                    bytes = content.bytes,
                    contentType = "application/octet-stream",
                    lastModifiedHint = content.lastModified,
                )
                client.delete(resource.path)
            }.onFailure { error ->
                Timber.w(error, "Failed to migrate WebDAV media: %s", resource.path)
            }
        }

        // Try to remove the now-empty old directory
        try {
            client.delete(oldFolder)
        } catch (_: Exception) {
            // Not critical
        }
    }

    // ── Git ─────────────────────────────────────────────────────────────

    /**
     * Detects root-level `.md` files and old `images/`/`voice/` folders in a Git
     * repo working tree and moves them into the layout-defined subdirectories.
     *
     * Only applies when [SyncDirectoryLayout.allSameDirectory] is `false`.
     * Returns `true` if any files were moved (caller should commit).
     */
    fun migrateGitRepo(
        repoRootDir: File,
        layout: SyncDirectoryLayout,
    ): Boolean {
        if (layout.allSameDirectory) return false

        var changed = false

        // Move root-level .md files into the memo subdirectory
        val memoSubDir = File(repoRootDir, layout.memoFolder)
        repoRootDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".md")) {
                if (!memoSubDir.exists()) memoSubDir.mkdirs()
                val target = File(memoSubDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                    changed = true
                }
            }
        }

        // Move old hardcoded media folders to layout-named folders
        changed = migrateGitMediaFolder(repoRootDir, "images", layout.imageFolder) || changed
        changed = migrateGitMediaFolder(repoRootDir, "voice", layout.voiceFolder) || changed

        return changed
    }

    private fun migrateGitMediaFolder(
        repoRootDir: File,
        oldFolderName: String,
        newFolderName: String,
    ): Boolean {
        val oldDir = File(repoRootDir, oldFolderName)
        if (oldFolderName == newFolderName || !oldDir.exists() || !oldDir.isDirectory) return false

        val newDir = File(repoRootDir, newFolderName)
        if (!newDir.exists()) newDir.mkdirs()

        var moved = false
        oldDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val target = File(newDir, file.name)
            if (!target.exists()) {
                file.renameTo(target)
                moved = true
            }
        }

        // Remove old directory if now empty
        if (oldDir.listFiles()?.isEmpty() == true) {
            oldDir.delete()
        }
        return moved
    }

    private fun resolveLegacyWebDavState(
        rootFiles: List<com.lomo.data.webdav.WebDavRemoteResource>,
    ): LegacyWebDavState {
        val oldMemos = rootFiles.filter { !it.isDirectory && it.path.endsWith(".md") }
        val hasOldImages = rootFiles.any { it.isDirectory && it.path.trimEnd('/') == LEGACY_IMAGE_FOLDER }
        val hasOldVoice = rootFiles.any { it.isDirectory && it.path.trimEnd('/') == LEGACY_VOICE_FOLDER }
        val hasLomoRoot = rootFiles.any { it.isDirectory && it.path.trimEnd('/') == LOMO_ROOT_FOLDER }
        return LegacyWebDavState(
            oldMemos = oldMemos,
            needsMigration = oldMemos.isNotEmpty() || hasOldImages || hasOldVoice,
            hasLomoRoot = hasLomoRoot,
        )
    }

    private data class LegacyWebDavState(
        val oldMemos: List<com.lomo.data.webdav.WebDavRemoteResource>,
        val needsMigration: Boolean,
        val hasLomoRoot: Boolean,
    )
}
