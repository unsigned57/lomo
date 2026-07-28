package com.lomo.data.engine.sync

import com.lomo.domain.model.RemoteSyncBackendLabel
import com.lomo.domain.model.RemoteSyncBinaryConflictFacts
import com.lomo.domain.model.RemoteSyncCenterFailure
import com.lomo.domain.model.RemoteSyncConfigSummary
import com.lomo.domain.model.RemoteSyncConflictPage
import com.lomo.domain.model.RemoteSyncConflictPath
import com.lomo.domain.model.RemoteSyncConflictPathStatus
import com.lomo.domain.model.RemoteSyncConflictResolution
import com.lomo.domain.model.RemoteSyncConflictResolveResult
import com.lomo.domain.model.RemoteSyncMarkdownConflictFacts
import com.lomo.domain.model.RemoteSyncSessionPhase
import com.lomo.domain.model.RemoteSyncSessionProgress
import com.lomo.domain.repository.RemoteSyncCenterRepository
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import com.lomo.data.engine.sync.RemoteSyncConflictPage as DataConflictPage
import com.lomo.data.engine.sync.RemoteSyncConflictPath as DataConflictPath
import com.lomo.data.engine.sync.RemoteSyncConflictPathStatus as DataPathStatus
import com.lomo.data.engine.sync.RemoteSyncConflictResolution as DataConflictResolution
import com.lomo.data.engine.sync.RemoteSyncConflictResolveResult as DataConflictResolveResult

/**
 * Stage-5 dark adapter: [RemoteSyncRepository] / BoltFFI facts → domain
 * [RemoteSyncCenterRepository] (Wave-4 / P5-10 residual close).
 *
 * Mapping + optional durable artifact body load for markdown detail. Registered in
 * [com.lomo.data.di.SyncDataModule] / navigation / presentation DI post P5-13.
 *
 * Config/session are presentation stubs until production scheduler cutover (honest null/idle
 * shells when no owner is injected).
 */
class RemoteSyncCenterRepositoryAdapter(
    private val remoteSync: RemoteSyncRepository,
    private val artifactSource: ConflictArtifactSource,
    private val configSummaryProvider: (String) -> RemoteSyncConfigSummary = {
        RemoteSyncConfigSummary(
            backend = RemoteSyncBackendLabel.None,
            attentionCount = 0,
            lastVerifiedAtEpochMillis = null,
            schedulePolicyLabel = null,
        )
    },
    private val sessionProgressProvider: (String) -> RemoteSyncSessionProgress = {
        RemoteSyncSessionProgress(
            phase = RemoteSyncSessionPhase.Idle,
            completedActions = 0,
            totalActions = null,
            canCancel = false,
        )
    },
) : RemoteSyncCenterRepository {
    override fun configSummary(workspaceRoot: String): RemoteSyncConfigSummary =
        configSummaryProvider(workspaceRoot)

    override fun sessionProgress(workspaceRoot: String): RemoteSyncSessionProgress =
        sessionProgressProvider(workspaceRoot)

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
            RemoteSyncMarkdownConflictFacts(
                path = path.path,
                baseDigest = path.baselineDigest,
                localDigest = path.localDigest,
                remoteDigest = path.remoteDigest,
                baseBody = readUtf8Artifact(workspaceRoot, path.baselineArtifactRef),
                localBody = readUtf8Artifact(workspaceRoot, path.localArtifactRef),
                remoteBody = readUtf8Artifact(workspaceRoot, path.remoteArtifactRef),
                mergedDraft = mergedDraft,
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
        sessionId = sessionId,
        conflictRevision = conflictRevision,
        items = items.map { it.toDomain() },
        nextCursor = nextCursor,
    )

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
