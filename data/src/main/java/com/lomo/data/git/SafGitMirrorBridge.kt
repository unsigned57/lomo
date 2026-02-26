package com.lomo.data.git

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLConnection
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafGitMirrorBridge
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun mirrorDirectoryFor(rootUriString: String): File =
            withContext(Dispatchers.IO) {
                val baseDir = File(context.filesDir, "git_sync_mirror")
                if (!baseDir.exists()) baseDir.mkdirs()

                val mirrorDir = File(baseDir, sha256(rootUriString))
                if (!mirrorDir.exists()) mirrorDir.mkdirs()
                mirrorDir
            }

        suspend fun pullFromSaf(rootUriString: String, mirrorDir: File) {
            withContext(Dispatchers.IO) {
                val root = resolveRootDocument(rootUriString)
                if (!mirrorDir.exists()) mirrorDir.mkdirs()

                val safPaths = mutableSetOf<String>()
                root.listFiles().forEach { child ->
                    val name = child.name ?: return@forEach
                    if (name == ".git") return@forEach
                    copySafDocumentToLocal(child, mirrorDir, name, safPaths)
                }
                pruneLocalMirror(mirrorDir, safPaths)
            }
        }

        suspend fun pushToSaf(rootUriString: String, mirrorDir: File) {
            withContext(Dispatchers.IO) {
                val root = resolveRootDocument(rootUriString)
                if (!mirrorDir.exists()) mirrorDir.mkdirs()

                val localPaths = mutableSetOf<String>()
                mirrorDir.walkTopDown().forEach { local ->
                    if (local == mirrorDir) return@forEach

                    val relativePath = local.relativeTo(mirrorDir).invariantSeparatorsPath
                    if (relativePath == ".git" || relativePath.startsWith(".git/")) return@forEach

                    localPaths.add(relativePath)
                    if (local.isDirectory) {
                        ensureSafDirectory(root, relativePath)
                    } else if (local.isFile) {
                        writeLocalFileToSaf(root, relativePath, local)
                    }
                }

                pruneSaf(root, localPaths)
            }
        }

        private fun resolveRootDocument(rootUriString: String): DocumentFile {
            val rootUri = Uri.parse(rootUriString)
            val root = DocumentFile.fromTreeUri(context, rootUri)
            if (root == null || !root.exists() || !root.isDirectory) {
                throw IOException("Invalid SAF root URI: $rootUriString")
            }
            return root
        }

        private fun copySafDocumentToLocal(
            document: DocumentFile,
            mirrorDir: File,
            relativePath: String,
            safPaths: MutableSet<String>,
        ) {
            if (document.isDirectory) {
                safPaths.add(relativePath)
                val localDir = File(mirrorDir, relativePath)
                if (!localDir.exists()) localDir.mkdirs()

                document.listFiles().forEach { child ->
                    val name = child.name ?: return@forEach
                    if (name == ".git") return@forEach
                    copySafDocumentToLocal(
                        child,
                        mirrorDir,
                        "$relativePath/$name",
                        safPaths,
                    )
                }
                return
            }

            if (!document.isFile) return

            safPaths.add(relativePath)
            val localFile = File(mirrorDir, relativePath)
            localFile.parentFile?.mkdirs()

            val input =
                context.contentResolver.openInputStream(document.uri)
                    ?: throw IOException("Failed to open input stream for ${document.uri}")
            input.use { source ->
                FileOutputStream(localFile, false).use { sink ->
                    source.copyTo(sink)
                }
            }

            val modified = document.lastModified()
            if (modified > 0) {
                localFile.setLastModified(modified)
            }
        }

        private fun pruneLocalMirror(
            mirrorDir: File,
            safPaths: Set<String>,
        ) {
            mirrorDir.walkBottomUp().forEach { local ->
                if (local == mirrorDir) return@forEach

                val relativePath = local.relativeTo(mirrorDir).invariantSeparatorsPath
                if (relativePath == ".git" || relativePath.startsWith(".git/")) return@forEach

                if (!safPaths.contains(relativePath)) {
                    val deleted = local.deleteRecursively()
                    if (!deleted) {
                        Timber.w("Failed to delete stale mirror path: %s", relativePath)
                    }
                }
            }
        }

        private fun writeLocalFileToSaf(
            root: DocumentFile,
            relativePath: String,
            localFile: File,
        ) {
            val parentPath = relativePath.substringBeforeLast('/', "")
            val filename = relativePath.substringAfterLast('/')
            val parent = ensureSafDirectory(root, parentPath)

            var target = parent.findFile(filename)
            if (target != null && target.isDirectory) {
                deleteRecursively(target)
                target = null
            }
            if (target == null) {
                target =
                    parent.createFile(mimeTypeFor(filename), filename)
                        ?: throw IOException("Failed to create SAF file: $relativePath")
            }

            val output =
                context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: context.contentResolver.openOutputStream(target.uri, "w")
                    ?: throw IOException("Failed to open output stream for ${target.uri}")
            output.use { sink ->
                FileInputStream(localFile).use { source ->
                    source.copyTo(sink)
                }
            }
        }

        private fun ensureSafDirectory(
            root: DocumentFile,
            relativePath: String,
        ): DocumentFile {
            if (relativePath.isBlank()) return root

            var current = root
            relativePath.split('/').filter { it.isNotBlank() }.forEach { segment ->
                var child = current.findFile(segment)
                if (child != null && !child.isDirectory) {
                    child.delete()
                    child = null
                }
                if (child == null) {
                    child =
                        current.createDirectory(segment)
                            ?: throw IOException("Failed to create SAF directory: $relativePath")
                }
                current = child
            }
            return current
        }

        private fun pruneSaf(
            root: DocumentFile,
            localPaths: Set<String>,
        ) {
            val safEntries = mutableListOf<Pair<String, DocumentFile>>()
            collectSafEntries(root, "", safEntries)

            safEntries
                .sortedByDescending { (path, _) -> path.count { it == '/' } }
                .forEach { (path, doc) ->
                    if (path == ".git" || path.startsWith(".git/")) return@forEach
                    if (!localPaths.contains(path)) {
                        deleteRecursively(doc)
                    }
                }
        }

        private fun collectSafEntries(
            parent: DocumentFile,
            basePath: String,
            sink: MutableList<Pair<String, DocumentFile>>,
        ) {
            parent.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val path = if (basePath.isBlank()) name else "$basePath/$name"
                sink.add(path to child)
                if (child.isDirectory) {
                    collectSafEntries(child, path, sink)
                }
            }
        }

        private fun deleteRecursively(document: DocumentFile) {
            if (document.isDirectory) {
                document.listFiles().forEach { child ->
                    deleteRecursively(child)
                }
            }
            if (!document.delete()) {
                Timber.w("Failed to delete SAF document: %s", document.uri)
            }
        }

        private fun mimeTypeFor(filename: String): String =
            URLConnection.guessContentTypeFromName(filename) ?: "application/octet-stream"

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
