package com.lomo.data.engine.sync

import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncCenterFailure
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncConflictSessionState
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.repository.RemoteSyncCenterRepository
import kotlinx.coroutines.flow.first
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import com.lomo.data.engine.sync.RemoteSyncConflictPage as DataConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictPath as DataConflictPath
import com.lomo.data.engine.sync.RemoteSyncConflictPathStatus as DataPathStatus
import com.lomo.data.engine.sync.RemoteSyncConflictResolution as DataConflictResolution
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult as DataConflictResolveResult
import com.lomo.data.sync.SyncConflictSuggestionPort
import com.lomo.data.engine.sync.RemoteSyncConflictSessionState as DataConflictSession

/**
 * Production adapter: [RemoteSyncRepository] / BoltFFI facts → domain
 * [RemoteSyncCenterRepository].
 *
 * `configSummary`/`sessionProgress`/`requestCancel` project the durable Rust cycle record
 * (`cycle_state.rec`/`cancel_request.rec`) — the sole status authority. Config label fields
 * come from persisted settings via [LomoDataStore]. Mapping + optional durable artifact body
 * load for markdown detail.
 */
class RemoteSyncCenterRepositoryAdapter(
    private val remoteSync: RemoteSyncRepository,
    private val artifactSource: ConflictArtifactSource,
    private val suggestionPort: SyncConflictSuggestionPort,
    private val configSource: RemoteSyncConfigSource,
) : RemoteSyncCenterRepository {
    override suspend fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary {
        val status = mapBoundary { remoteSync.cycleStatus(workspaceRoot) }
        val backend = configSource.backendType()
        return RemoteSyncConfigSummary(
            backend = backend.toRemoteSyncBackendLabel(),
            attentionCount = status.openConflictCount,
            lastVerifiedAtEpochMillis = status.lastSuccessfulAtMs?.takeIf { it > 0L },
            schedulePolicyLabel = configSource.schedulePolicyLabel(backend),
        )
    }

    override suspend fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
        mapBoundary { remoteSync.cycleStatus(workspaceRoot).toSessionProgress() }

    override suspend fun requestCancel(workspaceRoot: String): RemoteSyncSessionProgress =
        mapBoundary { remoteSync.requestCancel(workspaceRoot).toSessionProgress() }

    override fun listConflicts(
        workspaceRoot: String,
        cursor: Int,
        limit: Int,
    ): RemoteSyncConflictPage =
        mapBoundary {
            remoteSync.listConflicts(workspaceRoot, cursor, limit).toDomain()
        }

    override fun resolveConflicts(
        workspaceRoot: String,
        expectedRevision: Long,
        resolutions: List<RemoteSyncConflictResolution>,
    ): RemoteSyncConflictResolveResult =
        mapBoundary {
            remoteSync
                .resolveConflicts(
                    workspaceRoot = workspaceRoot,
                    expectedRevision = expectedRevision,
                    resolutions = resolutions.map { it.toData() },
                ).toDomain()
        }

    override fun markdownConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
        mergedDraft: String?,
    ): RemoteSyncMarkdownConflictFacts {
        require(path.isMarkdown) { "markdownConflictFacts requires kind=markdown" }
        return mapBoundary {
            val localBody = readUtf8Artifact(workspaceRoot, path.localArtifactRef)
            val remoteBody = readUtf8Artifact(workspaceRoot, path.remoteArtifactRef)
            RemoteSyncMarkdownConflictFacts(
                path = path.path,
                baseDigest = path.baselineDigest,
                localDigest = path.localDigest,
                remoteDigest = path.remoteDigest,
                baseBody = readUtf8Artifact(workspaceRoot, path.baselineArtifactRef),
                localBody = localBody,
                remoteBody = remoteBody,
                mergedDraft = mergedDraft,
                suggestion =
                    suggestionPort.suggest(
                        localBody = localBody,
                        remoteBody = remoteBody,
                        localLastModifiedMs = null,
                        remoteLastModifiedMs = null,
                        isBinary = false,
                    ),
            )
        }
    }

    override fun binaryConflictFacts(
        workspaceRoot: String,
        path: RemoteSyncConflictPath,
    ): RemoteSyncBinaryConflictFacts {
        require(path.isBinary) { "binaryConflictFacts requires kind=binary" }
        // Binary detail never invents a text body preview from artifact bytes.
        // MIME/size remain null until a future owner port provides them (honest list-wire residual).
        return RemoteSyncBinaryConflictFacts(
            path = path.path,
            mimeType = null,
            sizeBytes = null,
            localDigest = path.localDigest,
            remoteDigest = path.remoteDigest,
            baselineDigest = path.baselineDigest,
            sourceLabel = "remote_sync",
        )
    }

    private fun readUtf8Artifact(
        workspaceRoot: String,
        artifactRef: String?,
    ): String? {
        if (artifactRef.isNullOrBlank()) {
            return null
        }
        val bytes = artifactSource.readArtifact(workspaceRoot, artifactRef)
        return decodeStrictUtf8(bytes)
    }
}

/**
 * Durable conflict artifact body source (relative refs under `.lomo/sync/v1/artifacts`).
 *
 * Production-shaped path uses [SyncNativeBridge.readConflictArtifact]; host tests inject fakes.
 */
interface ConflictArtifactSource {
    fun readArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray
}

/**
 * [ConflictArtifactSource] over [SyncNativeBridge] free-function conversion.
 *
 * Maps [RemoteSyncBoundaryFailure] from the bridge edge.
 */
class BridgeConflictArtifactSource(
    private val bridge: SyncNativeBridge,
) : ConflictArtifactSource {
    override fun readArtifact(
        workspaceRoot: String,
        artifactRef: String,
    ): ByteArray {
        require(workspaceRoot.isNotBlank()) { "workspace root must be non-blank" }
        require(artifactRef.isNotBlank()) { "artifact ref must be non-blank" }
        return try {
            bridge.readConflictArtifact(workspaceRoot, artifactRef)
        } catch (error: com.lomo.nativebridge.EngineError.Failure) {
            val failure = error.failure
            throw RemoteSyncBoundaryFailure(
                category = failure.category,
                code = failure.code,
                retryDisposition = failure.retryDisposition,
                diagnostic = failure.diagnostic,
                operationId = failure.operationId,
                jobId = failure.jobId,
            ).also { mapped -> mapped.initCause(error) }
        }
    }
}

/**
 * Strict UTF-8 decode for markdown conflict bodies.
 *
 * Invalid UTF-8 fails closed (no replacement characters that invent preview text).
 */
internal fun decodeStrictUtf8(bytes: ByteArray): String {
    val decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw RemoteSyncBoundaryFailure(
            category = "validation",
            code = "conflict_artifact_invalid_utf8",
            retryDisposition = "never",
            diagnostic = "conflict artifact is not valid UTF-8",
        )
    }
}

private inline fun <T> mapBoundary(block: () -> T): T =
    try {
        block()
    } catch (error: RemoteSyncBoundaryFailure) {
        throw error.toCenterFailure()
    }

private fun RemoteSyncBoundaryFailure.toCenterFailure(): RemoteSyncCenterFailure =
    RemoteSyncCenterFailure(
        category = category,
        code = code,
        retryDisposition = retryDisposition,
        diagnostic = diagnostic,
        operationId = operationId,
        jobId = jobId,
    )

private fun DataConflictPage.toDomain(): RemoteSyncConflictPage =
    RemoteSyncConflictPage(
        session = session.toDomain(),
        sessionId = sessionId,
        conflictRevision = conflictRevision,
        items = items.map { it.toDomain() },
        nextCursor = nextCursor,
    )

private fun DataConflictSession.toDomain(): RemoteSyncConflictSessionState =
    when (this) {
        DataConflictSession.Absent -> RemoteSyncConflictSessionState.Absent
        DataConflictSession.Present -> RemoteSyncConflictSessionState.Present
    }

private fun DataConflictPath.toDomain(): RemoteSyncConflictPath =
    RemoteSyncConflictPath(
        path = path,
        kind = kind,
        localDigest = localDigest,
        remoteDigest = remoteDigest,
        baselineDigest = baselineDigest,
        remoteTokenPresent = remoteTokenPresent,
        localArtifactRef = localArtifactRef,
        remoteArtifactRef = remoteArtifactRef,
        baselineArtifactRef = baselineArtifactRef,
        status = status.toDomain(),
    )

private fun DataPathStatus.toDomain(): RemoteSyncConflictPathStatus =
    when (this) {
        DataPathStatus.Open -> RemoteSyncConflictPathStatus.Open
        DataPathStatus.ResolvedKeepLocal -> RemoteSyncConflictPathStatus.ResolvedKeepLocal
        DataPathStatus.ResolvedKeepRemote -> RemoteSyncConflictPathStatus.ResolvedKeepRemote
        DataPathStatus.ResolvedMerged -> RemoteSyncConflictPathStatus.ResolvedMerged
        DataPathStatus.SkippedForNow -> RemoteSyncConflictPathStatus.SkippedForNow
    }

private fun RemoteSyncConflictResolution.toData(): DataConflictResolution =
    DataConflictResolution(
        path = path,
        kind = kind,
        mergedBody = mergedBody,
    )

private fun DataConflictResolveResult.toDomain(): RemoteSyncConflictResolveResult =
    RemoteSyncConflictResolveResult(
        sessionId = sessionId,
        conflictRevision = conflictRevision,
        appliedPaths = appliedPaths,
    )

private fun SyncBackendType.toRemoteSyncBackendLabel(): RemoteSyncBackendLabel =
    when (this) {
        SyncBackendType.GIT -> RemoteSyncBackendLabel.Git
        SyncBackendType.WEBDAV -> RemoteSyncBackendLabel.WebDav
        SyncBackendType.S3 -> RemoteSyncBackendLabel.S3
        SyncBackendType.NONE,
        SyncBackendType.INBOX,
        -> RemoteSyncBackendLabel.None
        SyncBackendType.UNKNOWN -> RemoteSyncBackendLabel.Unknown
    }

/**
 * Persisted remote-sync config read surface for the Sync Center projection.
 *
 * Production impl reads [LomoDataStore]; the adapter itself only consumes it — status facts
 * stay owned by the durable Rust cycle record.
 */
interface RemoteSyncConfigSource {
    suspend fun backendType(): SyncBackendType

    /** `auto:<interval>` / `manual` for a configured remote backend; `null` for none/inbox. */
    suspend fun schedulePolicyLabel(backend: SyncBackendType): String?
}

class DataStoreRemoteSyncConfigSource(
    private val dataStore: LomoDataStore,
) : RemoteSyncConfigSource {
    override suspend fun backendType(): SyncBackendType =
        SyncBackendType.fromStorageValue(dataStore.syncBackendType.first())

    override suspend fun schedulePolicyLabel(backend: SyncBackendType): String? =
        when (backend) {
            SyncBackendType.GIT ->
                scheduleLabel(dataStore.gitAutoSyncEnabled.first(), dataStore.gitAutoSyncInterval.first())
            SyncBackendType.WEBDAV ->
                scheduleLabel(dataStore.webDavAutoSyncEnabled.first(), dataStore.webDavAutoSyncInterval.first())
            SyncBackendType.S3 ->
                scheduleLabel(dataStore.s3AutoSyncEnabled.first(), dataStore.s3AutoSyncInterval.first())
            SyncBackendType.NONE,
            SyncBackendType.INBOX,
            SyncBackendType.UNKNOWN,
            -> null
        }

    private fun scheduleLabel(
        autoEnabled: Boolean,
        interval: String,
    ): String = if (autoEnabled) "auto:$interval" else "manual"
}
