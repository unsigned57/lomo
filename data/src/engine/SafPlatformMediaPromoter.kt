package com.lomo.data.engine

import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.MediaPromotePlanDto
import com.lomo.nativebridge.MediaStagedDto
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Executes media promotions into the capability-bound SAF workspace before memo body commit.
 *
 * SAF promotion is the platform execution of the Rust `lomo_media::promote_staged` law; the two
 * sides share one promote contract and must not diverge:
 * - an identical length+digest target is already satisfied and only consumes the staged file,
 * - a different digest at the final path is `promote_final_path_conflict` and refuses the mutation
 *   (Rust suggests human-name destinations with no uniquifier, so a silent overwrite would corrupt
 *   an already committed memo's media),
 * - otherwise the staged bytes are verified against the staged facts and written as a new document.
 *
 * Unlike Rust's atomic rename, a crash mid-write can leave a partial document at the final path; the
 * next promote then refuses with `promote_final_path_conflict` instead of repairing it. That is the
 * accepted parity with Rust's cross-device copy fallback and keeps "never silently rewrite committed
 * media" the stronger invariant.
 *
 * Enforces the same-operation promote invariant (D4), destination path validation, staged file
 * length/digest verification, and idempotent replay. Media bytes are streamed chunk-wise and never
 * materialized in memory, so promotion cost is bounded by the copy chunk, not the media size.
 */
internal fun promoteSafMediaToWorkspace(
    treeUri: String,
    documents: PlatformDocumentsGateway,
    promotes: List<MediaPromotePlanDto>,
    operationId: String,
) {
    for (plan in promotes) {
        val finalRelativePath = validatePromotePlan(plan, operationId)
        val stagedFile = File(plan.staged.stagingPath)
        val existing = documents.stat(treeUri, WorkspaceTarget.Relative(finalRelativePath))
        if (existing != null) {
            if (existing.kind != DocumentKind.FILE) {
                throw engineCommandFailure(
                    category = EngineFailureCategory.CONFLICT,
                    code = "media_target_not_file",
                    retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
                    diagnostic = "SAF destination exists but is not a file: $finalRelativePath",
                    operationId = operationId,
                )
            }
            if (existing.length == plan.staged.size && existing.digest == plan.staged.digest) {
                stagedFile.takeIf { it.exists() }?.delete()
                continue
            }
            throw engineCommandFailure(
                category = EngineFailureCategory.VALIDATION,
                code = "promote_final_path_conflict",
                retryDisposition = EngineRetryDisposition.NEVER,
                diagnostic =
                    "final media path exists with a different digest: $finalRelativePath " +
                        "(committed=${existing.digest}, staged=${plan.staged.digest})",
                operationId = operationId,
            )
        }
        verifyStagedFile(stagedFile, plan.staged, operationId)
        val mimeType = plan.staged.mime.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        documents.writeFromFile(
            treeUri = treeUri,
            path = finalRelativePath,
            source = stagedFile,
            mode = WriteMode.CREATE,
            mimeType = mimeType,
        )
        stagedFile.delete()
    }
}

private fun validatePromotePlan(
    plan: MediaPromotePlanDto,
    operationId: String,
): String {
    if (plan.operationId != operationId) {
        throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "promote_operation_id_mismatch",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "pending promote operation_id must match the memo operation-id",
            operationId = operationId,
        )
    }
    val finalRelativePath = plan.finalRelativePath.trim()
    if (finalRelativePath.isEmpty() || !isValidWorkspacePath(finalRelativePath)) {
        throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "invalid_media_destination_path",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "destination path must be a valid workspace-relative path: $finalRelativePath",
            operationId = operationId,
        )
    }
    return finalRelativePath
}

/**
 * Verifies the staged file against its staged facts before any workspace write, streaming so media
 * size never bounds memory. Retry cannot help: the stage file is the only copy of this content and
 * every disposition here is [EngineRetryDisposition.NEVER].
 */
private fun verifyStagedFile(
    stagedFile: File,
    staged: MediaStagedDto,
    operationId: String,
) {
    if (!stagedFile.isFile) {
        throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "staged_media_missing",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "staged media file missing at ${staged.stagingPath}",
            operationId = operationId,
        )
    }
    val observed = stagedFile.inputStream().buffered().use { input -> digestAndLengthOf(input) }
    if (observed.length != staged.size.toLong()) {
        throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "staged_media_size_mismatch",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "staged media file size mismatch at ${staged.stagingPath}",
            operationId = operationId,
        )
    }
    if (observed.digest != staged.digest) {
        throw engineCommandFailure(
            category = EngineFailureCategory.VALIDATION,
            code = "staged_media_digest_mismatch",
            retryDisposition = EngineRetryDisposition.NEVER,
            diagnostic = "staged media file digest mismatch at ${staged.stagingPath}",
            operationId = operationId,
        )
    }
}

private fun digestAndLengthOf(input: InputStream): DigestObservation {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DIGEST_CHUNK_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        total += read
    }
    return DigestObservation(
        digest = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) },
        length = total,
    )
}

private data class DigestObservation(
    val digest: String,
    val length: Long,
)

private const val DIGEST_CHUNK_BYTES = 64 * 1024
