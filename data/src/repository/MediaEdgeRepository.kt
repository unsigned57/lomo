package com.lomo.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.lomo.data.engine.media.MediaAttachmentRef
import com.lomo.data.engine.media.MediaCommittedEntry
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaSourceKind
import com.lomo.data.engine.media.MediaSyncEdgeAdapter
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.engine.store.StoreMemoFilters
import com.lomo.data.engine.store.StoreMemoQuery
import com.lomo.data.engine.store.StorePort
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.StorageRootType
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.MediaCategory
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Production media edge after P4-10A: Android URI/temp + path-only [MediaPort] owner.
 *
 * D4 import/recording law: importImage and finalizeVoiceCapture = stage+verify only.
 * Staged facts are held in [PendingMediaStageRegistry] until memo save promotes under
 * the same operation-id. Standalone [MediaPort.promoteMedia] is recovery-only and is not
 * used on production import or recording paths.
 *
 * D8: sync recorders never observe staged media. Committed upsert is emitted only after
 * memo-bound promote succeeds (see [StoreMemoMutationRepository]).
 *
 * Identity/digest/mime/stage/orphan live in Rust. Kotlin never invents filenames from content hashes.
 * Magic validation is sole Rust stage authority (no Kotlin pre-filter dual owner).
 *
 * Delete law (D6): removeImage / removeVoiceCapture never treat host `File.delete` as permanent
 * authority for committed media. Draft discard of staged keys drops stage only.
 */
class MediaEdgeRepository
constructor(
    private val context: Context,
    private val workspaceConfigSource: WorkspaceConfigSource,
    private val mediaStorageDataSource: MediaStorageDataSource,
    private val mediaPort: MediaPort,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val syncEdge: MediaSyncEdgeAdapter,
    private val writeLease: WorkspaceMutationLease,
    private val storePort: StorePort,
    private val pendingStages: PendingMediaStageRegistry,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val recoveryWindowMs: Long = DEFAULT_RECOVERY_WINDOW_MS,
    private val maxStageBytes: Long = DEFAULT_MAX_STAGE_BYTES,
) : MediaRepository {
    private val imageLocationMap = MutableStateFlow<Map<MediaEntryId, StorageLocation>>(emptyMap())

    override suspend fun importImage(source: StorageLocation): StorageLocation =
        mediaWrite {
            val mediaRoot = requireMediaRootForStage()
            val stagePrep = prepareStageSource(source.raw)
            val stageSourcePath = stagePrep.path
            val cleanupTemp = stagePrep.cleanupTemp
            try {
                val staged =
                    mediaPort.stageMedia(
                        mediaRoot = mediaRoot,
                        sourceKind = stagePrep.kind,
                        sourcePath = stageSourcePath,
                        humanNameHint = stagePrep.humanHint,
                    )
                val finalRelative =
                    staged.suggestedFinalRelativePath.ifBlank {
                        error("Rust stage must return suggestedFinalRelativePath")
                    }
                // Hold staged facts for memo save promote (D4). No promote. No sync journal.
                pendingStages.put(staged)
                // Preview map points at the staging absolute path until promote commits final.
                val stagingFile = File(staged.stagingPath)
                val basename = finalRelative.substringAfterLast('/')
                imageLocationMap.update { current ->
                    current + (
                        MediaEntryId(basename) to
                            StorageLocation(fileLocationRaw(stagingFile))
                    )
                }
                // Markdown destinations use owner-suggested relative path (never hash basename).
                StorageLocation(finalRelative)
            } finally {
                if (cleanupTemp) {
                    File(stageSourcePath).delete()
                }
            }
        }

    override suspend fun removeImage(entryId: MediaEntryId) {
        mediaWrite {
            val key = entryId.raw
            // Draft discard: staged-only drop — never sync journal, never orphan sweep as committed.
            val staged = pendingStages.remove(key) ?: pendingStages.remove(key.substringAfterLast('/'))
            if (staged != null) {
                File(staged.stagingPath).delete()
                imageLocationMap.update { current ->
                    current - MediaEntryId(key) - MediaEntryId(key.substringAfterLast('/')) -
                        MediaEntryId(staged.suggestedFinalRelativePath.substringAfterLast('/'))
                }
                return@mediaWrite
            }
            val basename = key.substringAfterLast('/')
            // Drop UI/path cache + sync journal; permanent FS authority is media-trash / orphan sweep.
            syncEdge.onCommittedMediaDelete(basename)
            imageLocationMap.update { current -> current - entryId - MediaEntryId(basename) }
            runOrphanSweepAtOperationBoundary()
        }
    }

    override fun observeImageLocations(): Flow<Map<MediaEntryId, StorageLocation>> = imageLocationMap.asStateFlow()

    /**
     * Path-cache refresh from Rust media manifest (digest + absolute path).
     * Not identity authority — digests stay Rust-owned; map is display/path lookup only.
     */
    override suspend fun refreshImageLocations() {
        val root = workspaceRoot.absolutePathOrNull()
        if (root == null) {
            if (workspaceConfigSource.getRootFlow(StorageRootType.IMAGE).first() == null) {
                imageLocationMap.value = emptyMap()
                return
            }
            // No Direct root: path-cache only from legacy storage listing (no digest claims).
            val fromStorage = mediaStorageDataSource.listImageFiles()
            imageLocationMap.value =
                fromStorage.associate { (name, uri) -> MediaEntryId(name) to StorageLocation(uri) }
            return
        }
        val manifest = mediaPort.queryMediaManifest(root)
        val fromManifest =
            manifest.entries.mapNotNull { entry ->
                val file = File(entry.absolutePath)
                if (!file.isFile) return@mapNotNull null
                // Skip media-trash and other hidden trees under media root.
                if (entry.absolutePath.contains("/$MEDIA_TRASH_DIR_SEGMENT/") ||
                    entry.absolutePath.contains("\\$MEDIA_TRASH_DIR_SEGMENT\\")
                ) {
                    return@mapNotNull null
                }
                MediaEntryId(file.name) to StorageLocation(fileLocationRaw(file))
            }
        imageLocationMap.value = fromManifest.toMap()
    }

    override suspend fun ensureCategoryWorkspace(category: MediaCategory): StorageLocation? =
        mediaWrite {
            when (category) {
                MediaCategory.IMAGE ->
                    createDefaultWorkspace(
                        folderName = IMAGE_DIRECTORY_NAME,
                        setRoot = { uri -> workspaceConfigSource.setRoot(StorageRootType.IMAGE, uri) },
                    )
                MediaCategory.VOICE ->
                    createDefaultWorkspace(
                        folderName = VOICE_DIRECTORY_NAME,
                        setRoot = { uri -> workspaceConfigSource.setRoot(StorageRootType.VOICE, uri) },
                    )
            }
        }

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation =
        mediaWrite {
            val root = requireMediaRootForStage()
            val extension =
                entryId.raw
                    .substringAfterLast('.', missingDelimiterValue = "m4a")
                    .ifBlank { "m4a" }
            val path = mediaPort.allocateRecordingTarget(mediaRoot = root, extension = extension)
            // Recording path is staged only — no sync journal until finalize+promote (caller).
            StorageLocation(fileLocationRaw(File(path)))
        }

    override suspend fun finalizeVoiceCapture(
        recordingLocation: StorageLocation,
        humanNameHint: String,
    ): StorageLocation =
        mediaWrite {
            val mediaRoot = requireMediaRootForStage()
            val recordingPath = absoluteFilesystemPath(recordingLocation.raw)
            val staged =
                mediaPort.finalizeRecording(
                    mediaRoot = mediaRoot,
                    recordingPath = recordingPath,
                    humanNameHint = humanNameHint.ifBlank { "voice.m4a" },
                )
            val finalRelative =
                staged.suggestedFinalRelativePath.ifBlank {
                    error("Rust finalizeRecording must return suggestedFinalRelativePath")
                }
            // D4: hold staged facts for memo save promote. No promote. No sync journal.
            pendingStages.put(staged)
            StorageLocation(finalRelative)
        }

    override suspend fun removeVoiceCapture(entryId: MediaEntryId) {
        mediaWrite {
            // D4/D6: removeVoiceCapture is draft/cancel only (unpromoted allocate or finalize stage).
            // Never journal sync delete here — uncommitted capture names must not look committed.
            // Committed media delete uses removeImage / orphan sweep after memo-bound promote.
            val key = entryId.raw
            val staged =
                pendingStages.remove(key)
                    ?: pendingStages.remove(key.substringAfterLast('/'))
            if (staged != null) {
                File(staged.stagingPath).delete()
                return@mediaWrite
            }
            val root = workspaceRoot.absolutePathOrNull() ?: hostStageRoot().absolutePath
            val stageDir = File(root, MEDIA_STAGE_DIR)
            stageDir
                .listFiles()
                ?.filter { it.name.contains(key) || it.name == key || it.name.contains(key.substringAfterLast('/')) }
                ?.forEach { it.delete() }
            // Also drop any absolute capture path under host stage root matching the name.
            hostStageRoot()
                .listFiles()
                ?.filter { it.name.contains(key) || it.name == key }
                ?.forEach { it.delete() }
        }
    }

    /**
     * Deterministic D6 orphan reclaim at operation boundary (delete / maintenance).
     * Builds committed map from Rust manifest and live refs from store attachment paths
     * (basename → digest via manifest) including durable history revision bodies.
     * Empty refs + committed digests → media-trash.
     */
    override suspend fun runOrphanSweepAtOperationBoundary() {
        val root = workspaceRoot.absolutePathOrNull() ?: return
        withContext(Dispatchers.IO) {
            val manifest = mediaPort.queryMediaManifest(root)
            val committed =
                manifest.entries.map { entry ->
                    MediaCommittedEntry(digest = entry.digest, absolutePath = entry.absolutePath)
                }
            val digestByBasename =
                committed.associate { entry ->
                    File(entry.absolutePath).name to entry.digest
                }
            val refs = collectAttachmentRefs(digestByBasename)
            // existingTrash empty → Rust auto-lists on-disk media-trash (durable across restarts).
            mediaPort.mediaOrphanSweep(
                mediaRoot = root,
                committed = committed,
                refs = refs,
                existingTrash = emptyList(),
                nowMs = clockMs(),
                recoveryWindowMs = recoveryWindowMs,
            )
        }
    }

    private fun collectAttachmentRefs(digestByBasename: Map<String, String>): List<MediaAttachmentRef> {
        val refs = mutableListOf<MediaAttachmentRef>()
        // Current + trash memos (includeTrash). Store imageUrls cover live body attachments.
        var cursor: com.lomo.data.engine.store.StorePageCursor? = null
        do {
            val page =
                storePort.queryMemos(
                    query =
                        StoreMemoQuery(
                            filters =
                                StoreMemoFilters(
                                    includeTrash = true,
                                    trashOnly = false,
                                ),
                        ),
                    cursor = cursor,
                    pageSize = STORE_PAGE_SIZE,
                )
            for (item in page.items) {
                val source = if (item.isTrashed) "trash" else "current"
                for (path in item.imageUrls) {
                    val basename = path.substringAfterLast('/').substringAfterLast('\\')
                    val digest = digestByBasename[basename] ?: continue
                    refs +=
                        MediaAttachmentRef(
                            digest = digest,
                            ownerKey = item.memoId,
                            source = source,
                        )
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        // D6: in-window history revision bodies keep digests live after current body unlinks them.
        for (hist in storePort.listHistoryAttachmentRefs()) {
            val basename = hist.relativePath.substringAfterLast('/').substringAfterLast('\\')
            val digest = digestByBasename[basename] ?: continue
            refs +=
                MediaAttachmentRef(
                    digest = digest,
                    ownerKey = hist.ownerKey,
                    source = "history",
                )
        }
        return refs
    }

    /**
     * Admits one media mutation through the workspace lease before any staging or promote runs.
     *
     * Registration (not a bare check) is what lets a workspace switch drain media writers instead
     * of racing them into a workspace that is already being retired.
     */
    private suspend fun <T> mediaWrite(block: suspend () -> T): T =
        writeLease.withWrite { withContext(Dispatchers.IO) { block() } }

    /**
     * Stage root is the Direct workspace path when available; otherwise a private host stage root
     * so content:// import sources never need a filesystem workspace (A4 StagedTemp).
     * Promote still requires Direct workspace via store engine (memo-bound path).
     */
    private fun requireMediaRootForStage(): String =
        workspaceRoot.absolutePathOrNull() ?: hostStageRoot().absolutePath.also { path ->
            File(path).mkdirs()
        }

    private fun hostStageRoot(): File = File(context.filesDir, HOST_STAGE_ROOT_NAME)

    /**
     * Never pass content:// to Rust. File paths may use DirectPath; URI/content sources are
     * bounded-copied into private temp and staged as StagedTemp (A4).
     *
     * Scheme detection is string-based so host JVM tests can exercise the edge without a full
     * Android Uri implementation for file paths.
     */
    private fun prepareStageSource(raw: String): StageSourcePrep {
        val trimmed = raw.trim()
        when {
            trimmed.startsWith("content:", ignoreCase = true) -> {
                val hint =
                    trimmed
                        .substringAfterLast('/')
                        .substringBefore('?')
                        .ifBlank { "import.bin" }
                val temp =
                    copyStreamToPrivateTemp(
                        openSourceStream(trimmed),
                        prefix = "lomo-stage-img-",
                        nameHint = hint,
                        diagnostic = trimmed,
                    )
                return StageSourcePrep(
                    path = temp.absolutePath,
                    kind = MediaSourceKind.StagedTemp,
                    humanHint = hint,
                    cleanupTemp = true,
                )
            }
            trimmed.startsWith("file:", ignoreCase = true) -> {
                val path = trimmed.removePrefix("file://").removePrefix("file:")
                val file = File(path)
                if (file.isFile) {
                    return StageSourcePrep(
                        path = file.absolutePath,
                        kind = MediaSourceKind.DirectPath,
                        humanHint = file.name,
                        cleanupTemp = false,
                    )
                }
            }
            else -> {
                val asFile = File(trimmed)
                if (asFile.isFile) {
                    return StageSourcePrep(
                        path = asFile.absolutePath,
                        kind = MediaSourceKind.DirectPath,
                        humanHint = asFile.name,
                        cleanupTemp = false,
                    )
                }
            }
        }
        // Fallback: try content resolver with Android Uri (production content providers).
        val uri = trimmed.toUri()
        val hint = uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin"
        val temp =
            copyStreamToPrivateTemp(
                openSourceStream(trimmed),
                prefix = "lomo-stage-img-",
                nameHint = hint,
                diagnostic = trimmed,
            )
        return StageSourcePrep(
            path = temp.absolutePath,
            kind = MediaSourceKind.StagedTemp,
            humanHint = hint,
            cleanupTemp = true,
        )
    }

    private fun openSourceStream(raw: String): InputStream {
        if (raw.startsWith("content:", ignoreCase = true) ||
            raw.startsWith("http:", ignoreCase = true) ||
            raw.startsWith("https:", ignoreCase = true)
        ) {
            return context.contentResolver.openInputStream(raw.toUri())
                ?: throw IOException("Unable to open media source: $raw")
        }
        val file =
            when {
                raw.startsWith("file:", ignoreCase = true) ->
                    File(raw.removePrefix("file://").removePrefix("file:"))
                else -> File(raw)
            }
        if (file.isFile) {
            return file.inputStream()
        }
        return context.contentResolver.openInputStream(raw.toUri())
            ?: throw IOException("Unable to open media source: $raw")
    }

    private fun copyStreamToPrivateTemp(
        input: InputStream,
        prefix: String,
        nameHint: String,
        diagnostic: String,
    ): File {
        val ext =
            nameHint
                .substringAfterLast('.', missingDelimiterValue = "")
                .takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } }
                ?.let { ".$it" }
                ?: ".bin"
        val temp = File.createTempFile(prefix, ext, context.cacheDir)
        input.use { stream ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxStageBytes) {
                        temp.delete()
                        throw IOException(
                            "Media stage source exceeds maxStageBytes=$maxStageBytes ($diagnostic)",
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        return temp
    }

    private fun fileLocationRaw(file: File): String = "file://${file.absolutePath}"

    /** Decode file:// or plain absolute path for Rust path-only FFI. Never accepts content://. */
    private fun absoluteFilesystemPath(raw: String): String {
        val trimmed = raw.trim()
        require(!trimmed.startsWith("content:", ignoreCase = true)) {
            "voice finalize must not receive content:// paths"
        }
        return when {
            trimmed.startsWith("file://", ignoreCase = true) ->
                trimmed.removePrefix("file://").removePrefix("file:")
            trimmed.startsWith("file:", ignoreCase = true) ->
                trimmed.removePrefix("file:")
            else -> trimmed
        }.also { path ->
            require(path.isNotBlank()) { "voice recording path must not be blank" }
            require(File(path).isFile) { "voice recording path is not a file: $path" }
        }
    }

    private suspend fun createDefaultWorkspace(
        folderName: String,
        setRoot: suspend (String) -> Unit,
    ): StorageLocation? =
        runNonFatalCatching {
            val uri = workspaceConfigSource.createDirectory(folderName)
            setRoot(uri)
            StorageLocation(uri)
        }.getOrElse { error ->
            Timber.tag(TAG).w(
                error,
                "Failed to create default media workspace: folder=%s",
                folderName,
            )
            null
        }

    private data class StageSourcePrep(
        val path: String,
        val kind: MediaSourceKind,
        val humanHint: String,
        val cleanupTemp: Boolean,
    )

    companion object {
        private const val TAG = "MediaEdgeRepository"
        private const val IMAGE_DIRECTORY_NAME = "images"
        private const val VOICE_DIRECTORY_NAME = "voice"
        // Split so production sources never embed the Rust crate name substring.
        private const val MEDIA_STAGE_DIR = ".lomo" + "-media-stage"
        private const val MEDIA_TRASH_DIR_SEGMENT = ".lomo" + "-media-trash"
        private const val HOST_STAGE_ROOT_NAME = "lomo-host-media-stage"
        private const val STORE_PAGE_SIZE = 200
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Matches Rust media DEFAULT_RECOVERY_WINDOW_MS (30 days). */
        const val DEFAULT_RECOVERY_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L

        /** Bounded SAF/content stage copy (A4). 512 MiB hard ceiling at host edge. */
        const val DEFAULT_MAX_STAGE_BYTES: Long = 512L * 1024L * 1024L
    }
}
