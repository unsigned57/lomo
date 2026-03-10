package com.lomo.data.sync

import com.lomo.data.webdav.WebDavClient
import timber.log.Timber
import java.io.File

/**
 * One-time migration helpers that move files from the pre-layout structure
 * to the new directory layout.
 */
object SyncLayoutMigration {

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

        // Check if old-format data exists (root-level .md files or old images/voice dirs)
        val oldMemos = rootFiles.filter { !it.isDirectory && it.path.endsWith(".md") }
        val hasOldImages = rootFiles.any { it.isDirectory && it.path.trimEnd('/') == "images" }
        val hasOldVoice = rootFiles.any { it.isDirectory && it.path.trimEnd('/') == "voice" }
        val hasLomo = rootFiles.any { it.isDirectory && it.path.trimEnd('/') == "lomo" }

        if (oldMemos.isEmpty() && !hasOldImages && !hasOldVoice) return
        if (hasLomo) return // Already migrated or mixed state — don't touch

        Timber.i("SyncLayoutMigration: migrating WebDAV remote to lomo/ layout")

        // Ensure target directories
        client.ensureDirectory("lomo")
        for (folder in layout.distinctFolders) {
            client.ensureDirectory("lomo/$folder")
        }

        // Move memos
        for (memo in oldMemos) {
            val filename = memo.path.trimStart('/')
            try {
                val content = client.get(filename)
                client.put(
                    path = "lomo/${layout.memoFolder}/$filename",
                    bytes = content.bytes,
                    contentType = "text/markdown; charset=utf-8",
                    lastModifiedHint = content.lastModified,
                )
                client.delete(filename)
            } catch (e: Exception) {
                Timber.w(e, "Failed to migrate WebDAV memo: %s", filename)
            }
        }

        // Move media folders
        migrateWebDavMediaFolder(client, "images", "lomo/${layout.imageFolder}")
        migrateWebDavMediaFolder(client, "voice", "lomo/${layout.voiceFolder}")
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
            try {
                val content = client.get(resource.path)
                client.put(
                    path = "$newFolder/$filename",
                    bytes = content.bytes,
                    contentType = "application/octet-stream",
                    lastModifiedHint = content.lastModified,
                )
                client.delete(resource.path)
            } catch (e: Exception) {
                Timber.w(e, "Failed to migrate WebDAV media: %s", resource.path)
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
        if (oldFolderName == newFolderName) return false
        val oldDir = File(repoRootDir, oldFolderName)
        if (!oldDir.exists() || !oldDir.isDirectory) return false

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
}
