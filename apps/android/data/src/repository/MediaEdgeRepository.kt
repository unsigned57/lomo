package com.lomo.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.lomo.data.engine.PlatformActionAccessException
import com.lomo.data.engine.media.MediaCommittedEntry
import com.lomo.data.engine.media.MediaPort
import com.lomo.data.engine.media.MediaPromotePlan
import com.lomo.data.engine.media.MediaSourceKind
import com.lomo.data.engine.media.MediaStageRootProvider
import com.lomo.data.engine.media.MediaSweepDraftGuard
import com.lomo.data.engine.media.PendingMediaStageRegistry
import com.lomo.data.source.MediaStorageDataSource
import com.lomo.data.source.MediaStorageSelection
import com.lomo.data.source.WorkspaceConfigSource
import com.lomo.domain.model.DraftId
import com.lomo.domain.model.DraftMediaReconciliation
import com.lomo.domain.model.DraftStagedMediaRef
import com.lomo.domain.model.ImageLocationSnapshot
import com.lomo.domain.model.MediaEntryId
import com.lomo.domain.model.MediaImageDescriptor
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceMount
import com.lomo.domain.repository.MediaRepository
import com.lomo.domain.repository.MemoCreateDraftRepository
import com.lomo.domain.repository.MemoEditDraftRepository
import com.lomo.domain.repository.WorkspaceMutationLease
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Production media edge after P4-10A: Android URI/temp + path-only [MediaPort] owner.
 *
 * D4 import/recording law: importImage and finalizeVoiceCapture = stage+verify only.
 * Staged facts are held in [PendingMediaStageRegistry] until memo save promotes under
 * the same operation-id via `StoreMemoCommand.pendingPromotes`; there is no standalone
 * workspace promote on production import, inbox, or recording paths.
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
data class MediaEdgeRepositoryDependencies(
    val context: Context,
    val workspaceConfigSource: WorkspaceConfigSource,
    val mediaStorageDataSource: MediaStorageDataSource,
    val mediaPort: MediaPort,
    /** Engine-installed workspace; committed media facts are scoped to it. */
    val workspaceMount: StateFlow<WorkspaceMount>,
    val committedLocation: (String) -> StorageLocation?,
    val stageRoot: MediaStageRootProvider,
    val writeLease: WorkspaceMutationLease,
    val createDraftRepository: MemoCreateDraftRepository,
    val editDraftRepository: MemoEditDraftRepository,
)

data class MediaEdgeRepositoryLimits(
    val clockMs: () -> Long = { System.currentTimeMillis() },
    val recoveryWindowMs: Long = MediaEdgeRepository.DEFAULT_RECOVERY_WINDOW_MS,
    val maxStageBytes: Long = MediaEdgeRepository.DEFAULT_MAX_STAGE_BYTES,
)

class MediaEdgeRepository
constructor(
    dependencies: MediaEdgeRepositoryDependencies,
    private val pendingStages: PendingMediaStageRegistry,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    limits: MediaEdgeRepositoryLimits = MediaEdgeRepositoryLimits(),
) : MediaRepository,
    CommittedMediaLocationSink {
    private val context: Context = dependencies.context
    private val mediaStorageDataSource: MediaStorageDataSource = dependencies.mediaStorageDataSource
    private val mediaPort: MediaPort = dependencies.mediaPort
    private val committedLocation = dependencies.committedLocation
    private val stageRoot: MediaStageRootProvider = dependencies.stageRoot
    private val writeLease: WorkspaceMutationLease = dependencies.writeLease
    private val createDraftRepository: MemoCreateDraftRepository = dependencies.createDraftRepository
    private val editDraftRepository: MemoEditDraftRepository = dependencies.editDraftRepository
    private val clockMs: () -> Long = limits.clockMs
    private val recoveryWindowMs: Long = limits.recoveryWindowMs
    private val maxStageBytes: Long = limits.maxStageBytes

    private val workspaceMount: StateFlow<WorkspaceMount> = dependencies.workspaceMount

    private val committedMedia =
        MutableStateFlow(
            CommittedMediaLocations(
                workspace = null,
                generation = null,
                entries = emptyMap(),
                manifestHints = emptyList(),
            ),
        )

    private val inventoryRefresh = MutableStateFlow(0L)

    private val mediaSelections: Flow<MediaInventoryRequest> = workspaceMount
        .map(WorkspaceMount::mediaInventoryRequest)
        .distinctUntilChanged()

    /**
     * Applies an entry change under the installed workspace. Entries recorded under another
     * workspace are not carried over: their keys are relative to a different tree.
     */
    private fun updateCommittedEntries(
        preview: MediaEntryId? = null,
        promoted: Set<MediaEntryId> = emptySet(),
        transform: (Map<MediaEntryId, MediaImageDescriptor>) -> Map<MediaEntryId, MediaImageDescriptor>,
    ) {
        committedMedia.update { current ->
            val mount = workspaceMount.value
            val workspace = mount.location
            val generation = mount.mediaGeneration()
            CommittedMediaLocations(
                workspace = workspace,
                generation = generation,
                entries = transform(current.entriesFor(workspace, generation)),
                manifestHints = current.manifestHintsFor(workspace),
                previews = (current.previewsFor(workspace) + listOfNotNull(preview)) - promoted,
            )
        }
    }

    override suspend fun importImage(source: StorageLocation, draftId: DraftId): StorageLocation = mediaWrite {
        val mediaRoot = stageRoot.requireRoot()
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
            // Durable draft lease: the importing draft owns the staged bytes until submit/discard.
            val destinationName = staged.suggestedFinalRelativePath.substringAfterLast('/')
            val record = pendingStages.record(
                staged.copy(
                    suggestedFinalRelativePath = "images/$destinationName",
                ),
                draftId,
            )
            val finalRelative =
                record.suggestedFinalRelativePath.ifBlank {
                    error("Rust stage must return suggestedFinalRelativePath")
                }
            // Preview map keys the owner-resolved relative path — the exact
            // destination the draft body writes — and points at the staging
            // absolute path until promote commits final.
            val stagingFile = File(record.stagingPath)
            updateCommittedEntries(preview = MediaEntryId(finalRelative)) { current ->
                current + (
                    MediaEntryId(finalRelative) to
                        MediaImageDescriptor(
                            location = StorageLocation(fileLocationRaw(stagingFile)),
                            contentId = record.digest,
                        )
                    )
            }
            // Markdown destinations use the owner-resolved relative path (never hash basename).
            StorageLocation(finalRelative)
        } finally {
            if (cleanupTemp) {
                File(stageSourcePath).delete()
            }
        }
    }

    override suspend fun removeImage(entryId: MediaEntryId, draftId: DraftId) {
        mediaWrite {
            val key = entryId.raw
            // Draft discard: release exactly this draft's claim. Shared bytes another holder still
            // leases survive; the ledger deletes bytes only when the last claim is gone.
            val released = pendingStages.releaseDraftDestination(draftId, key)
            if (released != null) {
                updateCommittedEntries { current ->
                    current - MediaEntryId(key) - MediaEntryId(released)
                }
                return@mediaWrite
            }
            // A committed link in a discarded draft does not confer deletion authority.
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeImageLocations(): Flow<ImageLocationSnapshot> =
        combine(mediaSelections, inventoryRefresh) { selection, _ -> selection }
            .flatMapLatest { request ->
                val selection = request.directories
                // One cancellable load owns one immutable selection. Receipt updates below are
                // pure merges; importing an image cannot trigger another whole-tree scan.
                loadImageInventory(selection).combine(committedMedia) { inventory, committed ->
                    when (inventory) {
                        ImageLocationSnapshot.Loading -> inventory

                        is ImageLocationSnapshot.Unavailable -> inventory

                        is ImageLocationSnapshot.Ready -> {
                            val scoped = committed.entriesFor(
                                selection.workspaceLocation?.let(::StorageLocation),
                                request.generation,
                            )
                            // A current-generation native receipt is newer evidence than this
                            // directory observation; a newly committed file need not exist in it.
                            val entries = inventory.entries + scoped
                            ImageLocationSnapshot.Ready(entries)
                        }
                    }
                }
            }.distinctUntilChanged()

    private fun loadImageInventory(selection: MediaStorageSelection): Flow<ImageLocationSnapshot> = flow {
        if (!selection.needsProviderInventory()) {
            emit(ImageLocationSnapshot.Ready(emptyMap()))
            return@flow
        }
        emit(ImageLocationSnapshot.Loading)
        val result = try {
            val entries = withContext(dispatcherProvider.io) {
                buildProviderImageLocationMap(mediaStorageDataSource.listImageFiles(selection))
            }
            ImageLocationSnapshot.Ready(entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.w(failure, "Image inventory unavailable for %s", selection)
            ImageLocationSnapshot.Unavailable(
                code = when (failure) {
                    is PlatformActionAccessException -> failure.code
                    is SecurityException -> "saf_grant_revoked"
                    else -> "image_inventory_unavailable"
                },
                diagnostic = failure.message ?: failure.javaClass.simpleName,
            )
        }
        emit(result)
    }

    /**
     * Commit-receipt publication: a successful memo command already carries the promoted
     * artifacts' final relative paths and witnessed digests, so the location owner learns each
     * final location directly instead of re-walking the manifest. The staged preview entry keyed
     * by the same relative path is replaced by the final location under the same content identity.
     */
    override fun publishCommittedMedia(plans: List<MediaPromotePlan>) {
        if (plans.isEmpty()) return
        val locations = plans.associate { plan ->
            MediaEntryId(plan.finalRelativePath) to committedLocation(plan.finalRelativePath)?.let { location ->
                MediaImageDescriptor(location = location, contentId = plan.staged.digest)
            }
        }
        updateCommittedEntries(promoted = locations.keys) { current ->
            val next = current.toMutableMap()
            for ((entryId, descriptor) in locations) {
                if (descriptor == null) {
                    next.remove(entryId)
                } else {
                    next[entryId] = descriptor
                }
            }
            next
        }
    }

    /**
     * Path-cache refresh from Rust media manifest (digest + absolute path).
     * Not identity authority — digests stay Rust-owned; map is display/path lookup only.
     */
    override suspend fun refreshImageLocations() {
        val mount = workspaceMount.value
        val workspace = mount.location
        val generation = mount.mediaGeneration()
        if (mount.mediaInventoryRequest().directories.needsProviderInventory()) {
            // The inventory loader is the sole provider-scan path, including explicit retry.
            inventoryRefresh.update { it + 1 }
            return
        }
        val root = workspace
            ?.raw
            ?.takeUnless { it.startsWith("content:", ignoreCase = true) }
            ?.let { File(it).canonicalPath }
        if (root == null) {
            // No configured image directory and no Direct workspace means there is no media source.
            committedMedia.value = CommittedMediaLocations(workspace, generation, emptyMap(), emptyList())
            return
        }
        // Hints witnessed under another workspace describe a different tree and are never offered.
        val manifest = mediaPort.queryMediaManifest(root, committedMedia.value.manifestHintsFor(workspace))
        // The Rust manifest walker already excludes media-trash/stage/delete-intents trees.
        // Entries key on their workspace-relative path — directory identity,
        // not basename, so a nested hit and a root hit never collide in the map.
        val rootDir = File(root)
        val fromManifest =
            manifest.entries.mapNotNull { entry ->
                val file = File(entry.absolutePath)
                if (!file.isFile) return@mapNotNull null
                val relative =
                    file.relativeToOrNull(rootDir)?.invariantSeparatorsPath
                        ?: return@mapNotNull null
                MediaEntryId(relative) to
                    MediaImageDescriptor(
                        location = StorageLocation(fileLocationRaw(file)),
                        contentId = entry.digest,
                    )
            }
        committedMedia.value = CommittedMediaLocations(workspace, generation, fromManifest.toMap(), manifest.entries)
    }

    override suspend fun allocateVoiceCaptureTarget(entryId: MediaEntryId): StorageLocation = mediaWrite {
        val root = stageRoot.requireRoot()
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
        draftId: DraftId,
    ): StorageLocation = mediaWrite {
        val mediaRoot = stageRoot.requireRoot()
        val recordingPath = absoluteFilesystemPath(recordingLocation.raw)
        val staged =
            mediaPort.finalizeRecording(
                mediaRoot = mediaRoot,
                recordingPath = recordingPath,
                humanNameHint = humanNameHint.ifBlank { "voice.m4a" },
            )
        // D4: hold the draft's staged facts for memo save promote. No promote. No sync journal.
        val destinationName = staged.suggestedFinalRelativePath.substringAfterLast('/')
        val record = pendingStages.record(
            staged.copy(
                suggestedFinalRelativePath = "voice/$destinationName",
            ),
            draftId,
        )
        val finalRelative =
            record.suggestedFinalRelativePath.ifBlank {
                error("Rust finalizeRecording must return suggestedFinalRelativePath")
            }
        StorageLocation(finalRelative)
    }

    override suspend fun removeVoiceCapture(
        entryId: MediaEntryId,
        captureLocation: StorageLocation,
        draftId: DraftId,
    ) {
        mediaWrite {
            // D4/D6: removeVoiceCapture is draft/cancel only (unpromoted allocate or finalize stage).
            // Never journal sync delete here — uncommitted capture names must not look committed.
            // Committed media delete uses removeImage / orphan sweep after memo-bound promote.
            pendingStages.releaseDraftDestination(draftId, entryId.raw)
            // Delete exactly the bound capture target. A fuzzy name match could destroy a sibling
            // recording, so only the target the session actually wrote is removed.
            val target = plainFilesystemPath(captureLocation.raw)
            if (target.isNotEmpty()) {
                File(target).delete()
            }
        }
    }

    /**
     * Session-owned two-phase orphan sweep at an operation boundary (delete / maintenance).
     *
     * The Rust session enumerates `media/` candidates, then inside its transaction lock recomputes
     * the complete protection set — live and trashed bodies, in-window history revisions, conflict
     * drafts, frozen transactions, and stage-ledger leases — before moving unreferenced objects to
     * media-trash and purging expired entries. Kotlin supplies exactly one fact kind the Rust
     * draft store cannot see: the DataStore editor-draft bodies, passed as sweep-scoped guards so
     * media referenced only by an unsaved draft is never reclaimed under it. Wrapping the call in
     * `mediaWrite` keeps the sweep inside the workspace mutation lease so a workspace transition
     * drains it like every other writer.
     */
    override suspend fun runOrphanSweepAtOperationBoundary() {
        mediaWrite {
            mediaPort.sessionMediaOrphanSweep(
                nowMs = clockMs(),
                recoveryWindowMs = recoveryWindowMs,
                externalDrafts = externalDraftGuards(),
            )
        }
    }

    /**
     * Snapshot of the durable Kotlin editor drafts as sweep guards. Each draft's durable id is
     * the diagnostic owner; the Rust side projects the body inside the sweep lock and persists
     * nothing. A corrupt draft read surfaces through the repository contract rather than
     * silently dropping protection.
     */
    private suspend fun externalDraftGuards(): List<MediaSweepDraftGuard> = listOfNotNull(
        createDraftRepository.read()?.let { draft ->
            MediaSweepDraftGuard(ownerId = draft.draftId.value, content = draft.content)
        },
        editDraftRepository.read()?.let { draft ->
            MediaSweepDraftGuard(ownerId = draft.draftId.value, content = draft.content)
        },
    )

    /**
     * Restart reconciliation: reads this draft's lease rows straight from the Rust stage ledger,
     * including rows whose staged bytes vanished. The ledger remains the only authority — this is
     * a read view, never a second bookkeeping source.
     */
    override suspend fun reconcileDraftMedia(draftId: DraftId): DraftMediaReconciliation =
        withContext(dispatcherProvider.io) {
            DraftMediaReconciliation(
                draftId = draftId,
                records =
                pendingStages.draftRecords(draftId).map { record ->
                    DraftStagedMediaRef(
                        artifactId = record.artifactId,
                        relativePath = record.suggestedFinalRelativePath,
                        stagedBytesPresent = record.stagedBytesPresent,
                    )
                },
            )
        }

    /**
     * Drops every lease the draft still owns. Bytes only die when no other holder leases them, so
     * media re-leased to a frozen pending operation survives this cleanup. Also evicts the staged
     * preview entries so a discarded draft's media cannot resolve through the location map.
     */
    override suspend fun releaseDraftLeases(draftId: DraftId) {
        mediaWrite {
            val released = pendingStages.releaseDraft(draftId)
            if (released.isNotEmpty()) {
                val releasedKeys = released.mapTo(mutableSetOf()) { MediaEntryId(it.suggestedFinalRelativePath) }
                committedMedia.update { current ->
                    val removed = current.previews.intersect(releasedKeys)
                    current.copy(entries = current.entries - removed, previews = current.previews - removed)
                }
            }
        }
    }

    /**
     * Admits one media mutation through the workspace lease before any staging or promote runs.
     *
     * Registration (not a bare check) is what lets a workspace switch drain media writers instead
     * of racing them into a workspace that is already being retired.
     */
    private suspend fun <T> mediaWrite(block: suspend () -> T): T =
        writeLease.withWrite { withContext(dispatcherProvider.io) { block() } }

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
                val file = File(plainFilesystemPath(trimmed))
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
        val file = File(plainFilesystemPath(raw))
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
        val temp =
            // behavior-contract: blocking-io-ok: media staging is invoked from Dispatchers.IO workers
            File.createTempFile(prefix, ext, context.cacheDir)
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

    private fun fileLocationRaw(file: File): String = file.toPath().toUri().toASCIIString()

    /** Decode file:// or plain absolute path without requiring the file to exist yet. */
    private fun plainFilesystemPath(raw: String): String {
        val trimmed = raw.trim()
        require(!trimmed.startsWith("content:", ignoreCase = true)) {
            "voice capture path must not be a content:// path"
        }
        return if (trimmed.startsWith("file:", ignoreCase = true)) File(URI(trimmed)).path else trimmed
    }

    /** Resolves the recording's actual file before it reaches the path-only Rust boundary. */
    private fun absoluteFilesystemPath(raw: String): String = plainFilesystemPath(raw).also { path ->
        require(path.isNotBlank()) { "voice recording path must not be blank" }
        require(File(path).isFile) { "voice recording path is not a file: $path" }
    }

    private data class StageSourcePrep(
        val path: String,
        val kind: MediaSourceKind,
        val humanHint: String,
        val cleanupTemp: Boolean,
    )

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024

        /** Matches Rust media DEFAULT_RECOVERY_WINDOW_MS (30 days). */
        const val DEFAULT_RECOVERY_WINDOW_MS: Long = 30L * 24L * 60L * 60L * 1000L

        /** Bounded SAF/content stage copy (A4). 512 MiB hard ceiling at host edge. */
        const val DEFAULT_MAX_STAGE_BYTES: Long = 512L * 1024L * 1024L
    }
}

/**
 * Committed media facts scoped to the workspace that produced them. Entry keys are
 * workspace-relative paths and manifest hints describe one tree, so neither is meaningful
 * under another installed workspace; Rust still re-verifies path/size/mtime before trusting
 * any hint it is offered.
 */
private data class CommittedMediaLocations(
    val workspace: StorageLocation?,
    val generation: MediaInventoryGeneration?,
    val entries: Map<MediaEntryId, MediaImageDescriptor>,
    val manifestHints: List<MediaCommittedEntry>,
    val previews: Set<MediaEntryId> = emptySet(),
) {
    fun entriesFor(
        installed: StorageLocation?,
        installedGeneration: MediaInventoryGeneration?,
    ): Map<MediaEntryId, MediaImageDescriptor> = when {
        workspace != installed -> emptyMap()
        generation == installedGeneration -> entries
        else -> entries.filterKeys { it in previews }
    }

    fun previewsFor(installed: StorageLocation?): Set<MediaEntryId> =
        if (workspace == installed) previews else emptySet()

    fun manifestHintsFor(installed: StorageLocation?): List<MediaCommittedEntry> =
        if (workspace == installed) manifestHints else emptyList()
}

/**
 * The platform inventory already uses the session's logical workspace paths, with
 * provider-observed locations. Never add images/ to legacy or shared-root paths.
 */
private fun buildProviderImageLocationMap(
    providerFiles: List<Pair<String, String>>,
): Map<MediaEntryId, MediaImageDescriptor> = buildMap {
    for ((name, location) in providerFiles) {
        val descriptor = MediaImageDescriptor(location = StorageLocation(location), contentId = null)
        put(MediaEntryId(name), descriptor)
    }
}

private fun MediaStorageSelection.needsProviderInventory(): Boolean = imageLocation != null

/** Cache provenance is the installed native generation; ordinary projection revisions do not change bindings. */
private data class MediaInventoryGeneration(val workspaceId: String, val generation: Long)

private data class MediaInventoryRequest(
    val directories: MediaStorageSelection,
    val generation: MediaInventoryGeneration?,
)

private fun WorkspaceMount.mediaGeneration(): MediaInventoryGeneration? =
    authority?.let { MediaInventoryGeneration(it.workspaceId, it.generation) }

private fun WorkspaceMount.mediaInventoryRequest(): MediaInventoryRequest = MediaInventoryRequest(
    directories = MediaStorageSelection(location?.raw, attachments.images?.raw),
    generation = mediaGeneration(),
)
