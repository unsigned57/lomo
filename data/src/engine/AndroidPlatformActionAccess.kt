package com.lomo.data.engine

import com.lomo.nativebridge.ActionEvidence
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.DocumentKind
import com.lomo.nativebridge.DocumentMetadata
import com.lomo.nativebridge.EngineFailure
import com.lomo.nativebridge.ExpectedFingerprint
import com.lomo.nativebridge.MetadataPage
import com.lomo.nativebridge.PlatformAction
import com.lomo.nativebridge.PlatformActionOutput
import com.lomo.nativebridge.VerifiedAbsence
import com.lomo.nativebridge.WorkspaceTarget
import com.lomo.nativebridge.WriteMode
import java.io.ByteArrayInputStream

/**
 * Executes one platform action against a capability-bound SAF tree and private exchange files.
 *
 * Replay returns [ActionOutcome.AlreadySatisfied] only when the independently observed durable
 * postcondition already matches. Mismatched expected fingerprints fail closed without side effects.
 */
internal class AndroidPlatformActionAccess(
    private val registry: CapabilityRegistry,
    private val exchange: ExchangeResolver,
    private val documents: PlatformDocumentsGateway,
) : PlatformActionAccess {
    override fun execute(action: PlatformAction): ActionOutcome =
        try {
            when (action) {
                is PlatformAction.Stat -> executeStat(action)
                is PlatformAction.ListChildren -> executeList(action)
                is PlatformAction.EnsureDirectory -> executeEnsureDirectory(action)
                is PlatformAction.ReadToExchange -> executeReadToExchange(action)
                is PlatformAction.WriteFromExchange -> executeWriteFromExchange(action)
                is PlatformAction.Move -> executeMove(action)
                is PlatformAction.Delete -> executeDelete(action)
            }
        } catch (error: CapabilityRegistryException) {
            ActionOutcome.Failed(error.toFailure())
        } catch (error: ExchangeResolverException) {
            ActionOutcome.Failed(error.toFailure())
        } catch (error: PlatformActionAccessException) {
            ActionOutcome.Failed(error.toFailure())
        } catch (error: SecurityException) {
            ActionOutcome.Failed(
                EngineFailure(
                    category = "permission",
                    code = "saf_grant_revoked",
                    retryDisposition = "after_user_action",
                    operationId = null,
                    jobId = null,
                    diagnostic = error.message ?: "SAF permission is no longer available",
                ),
            )
        }

    private fun executeStat(action: PlatformAction.Stat): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        val snapshot =
            documents.stat(tree, action.target)
                ?: throw notFound("Target document is absent")
        return ActionOutcome.Applied(PlatformActionOutput.Stat(metadata = snapshot.toMetadata()))
    }

    private fun executeList(action: PlatformAction.ListChildren): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        if (action.pageSize !in 1u..MAX_METADATA_PAGE_SIZE) {
            throw PlatformActionAccessException(
                category = "resource_limit",
                code = "invalid_page_size",
                diagnostic = "page size must be within 1..=256",
            )
        }
        val page = documents.listChildren(tree, action.target, action.cursor, action.pageSize)
        if (page.items.size > action.pageSize.toInt()) {
            throw PlatformActionAccessException(
                category = "resource_limit",
                code = "metadata_page_limit_exceeded",
                diagnostic = "metadata page exceeded the requested page size",
            )
        }
        return ActionOutcome.Applied(
            PlatformActionOutput.Listed(
                page =
                    MetadataPage(
                        items = page.items.map { it.toMetadata() },
                        nextCursor = page.nextCursor,
                    ),
            ),
        )
    }

    private fun executeEnsureDirectory(action: PlatformAction.EnsureDirectory): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        validateWorkspacePath(action.path)
        val existing = documents.stat(tree, WorkspaceTarget.Relative(action.path))
        if (existing != null && existing.kind == DocumentKind.DIRECTORY) {
            return ActionOutcome.AlreadySatisfied(
                PlatformActionOutput.DirectoryReady(metadata = existing.toMetadata()),
            )
        }
        val created = documents.ensureDirectory(tree, action.path)
        return ActionOutcome.Applied(
            PlatformActionOutput.DirectoryReady(metadata = created.toMetadata()),
        )
    }

    private fun executeReadToExchange(action: PlatformAction.ReadToExchange): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        validateWorkspacePath(action.path)
        // Validate exchange token before any SAF I/O.
        exchange.resolveFile(action.exchangeToken)
        val handle =
            action.documentHandle?.let { documentHandle ->
                documents.openReadByHandle(tree, action.path, documentHandle)
            } ?: documents.openRead(tree, action.path)
        if (handle.snapshot.kind != DocumentKind.FILE) {
            throw PlatformActionAccessException(
                category = "validation",
                code = "document_not_file",
                diagnostic = "ReadToExchange requires a file target",
            )
        }
        when (val expected = action.expectedSource) {
            is ExpectedFingerprint.Absent -> Unit
            is ExpectedFingerprint.Match -> {
                if (handle.snapshot.toEvidence() != expected.evidence) {
                    throw postconditionMismatch(
                        "Source fingerprint does not match the expected postcondition",
                    )
                }
            }
        }
        val artifact =
            exchange.writeStreaming(
                token = action.exchangeToken,
                source = ByteArrayInputStream(handle.bytes),
            )
        return ActionOutcome.Applied(
            PlatformActionOutput.ReadToExchange(
                sourceMetadata = handle.snapshot.toMetadata(),
                artifact = artifact,
            ),
        )
    }

    private fun executeWriteFromExchange(action: PlatformAction.WriteFromExchange): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        validateWorkspacePath(action.path)
        val exchangeFile = exchange.resolveFile(action.artifact.token)
        if (!exchangeFile.isFile) {
            throw PlatformActionAccessException(
                category = "storage",
                code = "exchange_artifact_missing",
                diagnostic = "Exchange artifact is missing",
            )
        }
        val local = exchange.digestArtifact(action.artifact.token)
        if (local.length != action.artifact.length || local.digest != action.artifact.digest) {
            throw PlatformActionAccessException(
                category = "validation",
                code = "exchange_artifact_mismatch",
                diagnostic = "Exchange artifact length/digest does not match the action",
            )
        }
        val existing = documents.stat(tree, WorkspaceTarget.Relative(action.path))
        alreadySatisfiedWrite(action, existing)?.let { return it }
        assertWritePostcondition(action, existing)
        val written =
            documents.writeFromExchange(
                treeUri = tree,
                path = action.path,
                bytes = exchangeFile.readBytes(),
                mode = action.mode,
                mimeType = "application/octet-stream",
            )
        return ActionOutcome.Applied(
            PlatformActionOutput.WriteComplete(metadata = written.toMetadata()),
        )
    }


    private fun alreadySatisfiedWrite(
        action: PlatformAction.WriteFromExchange,
        existing: PlatformDocumentSnapshot?,
    ): ActionOutcome? {
        if (existing == null) return null
        val matchesArtifact =
            existing.length == action.artifact.length && existing.digest == action.artifact.digest
        return when (val expected = action.expectedTarget) {
            is ExpectedFingerprint.Absent ->
                if (matchesArtifact) {
                    ActionOutcome.AlreadySatisfied(
                        PlatformActionOutput.WriteComplete(metadata = existing.toMetadata()),
                    )
                } else {
                    null
                }
            is ExpectedFingerprint.Match -> {
                if (existing.toEvidence() != expected.evidence) return null
                if (!matchesArtifact) return null
                ActionOutcome.AlreadySatisfied(
                    PlatformActionOutput.WriteComplete(metadata = existing.toMetadata()),
                )
            }
        }
    }

    private fun assertWritePostcondition(
        action: PlatformAction.WriteFromExchange,
        existing: PlatformDocumentSnapshot?,
    ) {
        val reason =
            when (val expected = action.expectedTarget) {
                is ExpectedFingerprint.Absent ->
                    if (existing != null && action.mode == WriteMode.CREATE) {
                        "Create refused because the target already exists"
                    } else {
                        null
                    }
                is ExpectedFingerprint.Match ->
                    when {
                        existing == null -> "Expected target fingerprint but document is absent"
                        existing.toEvidence() != expected.evidence ->
                            "Target fingerprint does not match the expected postcondition"
                        else -> null
                    }
            }
        if (reason != null) {
            throw postconditionMismatch(reason)
        }
    }

    private fun executeMove(action: PlatformAction.Move): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        validateWorkspacePath(action.source)
        validateWorkspacePath(action.target)
        val source = documents.stat(tree, WorkspaceTarget.Relative(action.source))
        val target = documents.stat(tree, WorkspaceTarget.Relative(action.target))
        alreadySatisfiedMove(action, source, target)?.let { return it }
        assertMovePrecondition(action, source, target)
        val moved = documents.move(tree, action.source, action.target)
        return ActionOutcome.Applied(PlatformActionOutput.MoveComplete(metadata = moved.toMetadata()))
    }

    private fun alreadySatisfiedMove(
        action: PlatformAction.Move,
        source: PlatformDocumentSnapshot?,
        target: PlatformDocumentSnapshot?,
    ): ActionOutcome? {
        if (source != null || target == null) return null
        return when (val expected = action.expectedTarget) {
            is ExpectedFingerprint.Absent ->
                ActionOutcome.AlreadySatisfied(
                    PlatformActionOutput.MoveComplete(metadata = target.toMetadata()),
                )
            is ExpectedFingerprint.Match ->
                if (target.toEvidence() == expected.evidence) {
                    ActionOutcome.AlreadySatisfied(
                        PlatformActionOutput.MoveComplete(metadata = target.toMetadata()),
                    )
                } else {
                    null
                }
        }
    }

    private fun assertMovePrecondition(
        action: PlatformAction.Move,
        source: PlatformDocumentSnapshot?,
        target: PlatformDocumentSnapshot?,
    ) {
        val reason =
            when {
                source == null -> "Move source is absent without a satisfied target"
                action.expectedSource is ExpectedFingerprint.Match &&
                    source.toEvidence() != (action.expectedSource as ExpectedFingerprint.Match).evidence ->
                    "Move source fingerprint mismatch"
                action.expectedTarget is ExpectedFingerprint.Absent && target != null ->
                    "Move target already exists"
                action.expectedTarget is ExpectedFingerprint.Match &&
                    target != null &&
                    target.toEvidence() != (action.expectedTarget as ExpectedFingerprint.Match).evidence ->
                    "Move target fingerprint mismatch"
                else -> null
            }
        if (reason != null) {
            throw postconditionMismatch(reason)
        }
    }

    private fun executeDelete(action: PlatformAction.Delete): ActionOutcome {
        val tree = registry.resolve(action.capabilityToken)
        validateWorkspacePath(action.path)
        val existing = documents.stat(tree, WorkspaceTarget.Relative(action.path))
        if (existing == null) {
            // Durable postcondition for delete is absence.
            val fingerprint =
                when (val expected = action.expectedTarget) {
                    is ExpectedFingerprint.Match -> expected.evidence.fingerprint
                    is ExpectedFingerprint.Absent -> absenceFingerprint(action.path)
                }
            return ActionOutcome.AlreadySatisfied(
                PlatformActionOutput.DeleteComplete(
                    absence =
                        VerifiedAbsence(
                            target = WorkspaceTarget.Relative(action.path),
                            fingerprint = fingerprint,
                        ),
                ),
            )
        }
        when (val expected = action.expectedTarget) {
            is ExpectedFingerprint.Absent -> Unit
            is ExpectedFingerprint.Match -> {
                if (existing.toEvidence() != expected.evidence) {
                    throw postconditionMismatch("Delete target fingerprint mismatch")
                }
            }
        }
        documents.delete(tree, action.path)
        val fingerprint =
            when (val expected = action.expectedTarget) {
                is ExpectedFingerprint.Match -> expected.evidence.fingerprint
                is ExpectedFingerprint.Absent -> deletedFingerprint(action.path)
            }
        return ActionOutcome.Applied(
            PlatformActionOutput.DeleteComplete(
                absence =
                    VerifiedAbsence(
                        target = WorkspaceTarget.Relative(action.path),
                        fingerprint = fingerprint,
                    ),
            ),
        )
    }
}

private fun PlatformDocumentSnapshot.toMetadata(): DocumentMetadata =
    DocumentMetadata(
        target = target,
        documentHandle = documentId,
        kind = kind,
        mimeType = mimeType,
        evidence = toEvidence(),
    )

private fun PlatformDocumentSnapshot.toEvidence(): ActionEvidence =
    ActionEvidence(
        length = length,
        digest = digest,
        fingerprint =
            PlatformActionEvidence.fingerprint(
                documentId = documentId,
                lastModifiedEpochMillis = lastModifiedEpochMillis,
                length = length,
            ),
    )

private fun validateWorkspacePath(path: String) {
    if (!isValidWorkspacePath(path)) {
        throw PlatformActionAccessException(
            category = "validation",
            code = "invalid_workspace_path",
            diagnostic = "workspace path must be a bounded canonical relative UTF-8 path",
        )
    }
}

private fun isValidWorkspacePath(path: String): Boolean {
    if (path.isEmpty() || path.length > MAX_WORKSPACE_PATH_BYTES) return false
    if (path.startsWith('/') || path.contains('\\')) return false
    if (path.length >= 2 && path[1] == ':') return false
    if (path.any { it.isISOControl() }) return false
    return path.split('/').none { segment ->
        segment.isEmpty() || segment == "." || segment == ".." || segment.length > MAX_PATH_SEGMENT_BYTES
    }
}

private fun notFound(diagnostic: String): PlatformActionAccessException =
    PlatformActionAccessException(
        category = "storage",
        code = "document_not_found",
        diagnostic = diagnostic,
    )

private fun postconditionMismatch(diagnostic: String): PlatformActionAccessException =
    PlatformActionAccessException(
        category = "conflict",
        code = "platform_postcondition_mismatch",
        diagnostic = diagnostic,
    )

private fun absenceFingerprint(path: String): String =
    "absent.${path.sha256Hex().take(FINGERPRINT_SHORT_HEX_LENGTH)}"

private fun deletedFingerprint(path: String): String =
    "deleted.${path.sha256Hex().take(FINGERPRINT_SHORT_HEX_LENGTH)}"

private const val MAX_METADATA_PAGE_SIZE = 256u
private const val MAX_WORKSPACE_PATH_BYTES = 4096
private const val MAX_PATH_SEGMENT_BYTES = 255
private const val FINGERPRINT_SHORT_HEX_LENGTH = 40

private fun CapabilityRegistryException.toFailure(): EngineFailure =
    EngineFailure(
        category = category,
        code = code,
        retryDisposition = "after_user_action",
        operationId = null,
        jobId = null,
        diagnostic = diagnostic,
    )

private fun ExchangeResolverException.toFailure(): EngineFailure =
    EngineFailure(
        category = category,
        code = code,
        retryDisposition = "never",
        operationId = null,
        jobId = null,
        diagnostic = diagnostic,
    )

private class PlatformActionAccessException(
    val category: String,
    val code: String,
    val diagnostic: String,
) : RuntimeException("$code: $diagnostic") {
    fun toFailure(): EngineFailure =
        EngineFailure(
            category = category,
            code = code,
            retryDisposition =
                when (category) {
                    "conflict", "permission" -> "after_user_action"
                    "timeout" -> "transient"
                    else -> "never"
                },
            operationId = null,
            jobId = null,
            diagnostic = diagnostic,
        )
}

private fun String.sha256Hex(): String = toByteArray(Charsets.UTF_8).sha256Hex()
