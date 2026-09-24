package com.lomo.data.engine

import com.lomo.nativebridge.ActionEvidence
import com.lomo.nativebridge.ActionOutcome
import com.lomo.nativebridge.ContentDigest
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
 * Executes one platform action against a capability-bound SAF tree or Direct root.
 *
 * Replay returns [ActionOutcome.AlreadySatisfied] only when the independently observed durable
 * postcondition already matches. Mismatched expected fingerprints fail closed without side effects.
 */
internal class AndroidPlatformActionAccess(
    private val registry: CapabilityRegistry,
    private val exchange: ExchangeResolver,
    private val documents: PlatformDocumentsGateway,
    private val directDocuments: DirectRootDocumentsGateway = DirectRootDocumentsGateway(),
) : PlatformActionAccess {
    override fun execute(action: PlatformAction): ActionOutcome =
        try {
            when (action) {
                is PlatformAction.Stat -> executeStat(action)
                is PlatformAction.ListChildren -> executeList(action)
                is PlatformAction.EnsureDirectory -> executeEnsureDirectory(action)
                is PlatformAction.ReadToExchange -> executeReadToExchange(action)
                is PlatformAction.WriteFromExchange -> executeWriteFromExchange(action)
                is PlatformAction.ArtifactWrite -> executeArtifactWrite(action)
                is PlatformAction.Move -> executeMove(action)
                is PlatformAction.Delete -> executeDelete(action)
            }
        } catch (error: CapabilityRegistryException) {
            ActionOutcome.Failed(error.toFailure())
        } catch (error: DirectRootAccessException) {
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
        val tree = boundTree(action.capabilityToken)
        val snapshot =
            tree.stat(action.target)
                ?: throw notFound("Target document is absent")
        return ActionOutcome.Applied(PlatformActionOutput.Stat(metadata = snapshot.toMetadata()))
    }

    private fun executeList(action: PlatformAction.ListChildren): ActionOutcome {
        val tree = boundTree(action.capabilityToken)
        if (action.pageSize !in 1u..MAX_METADATA_PAGE_SIZE) {
            throw PlatformActionAccessException(
                category = "resource_limit",
                code = "invalid_page_size",
                diagnostic = "page size must be within 1..=256",
            )
        }
        val page = tree.listChildren(action.target, action.cursor, action.pageSize)
        val violation =
            when {
                // An unreadable directory must not be reported as an empty one: a scan would delete
                // every memo the provider simply failed to enumerate.
                page.incomplete ->
                    PlatformActionAccessException(
                        category = "storage",
                        code = "metadata_enumeration_incomplete",
                        diagnostic = "the platform document provider could not enumerate the target",
                    )

                page.items.size > action.pageSize.toInt() ->
                    PlatformActionAccessException(
                        category = "resource_limit",
                        code = "metadata_page_limit_exceeded",
                        diagnostic = "metadata page exceeded the requested page size",
                    )

                else -> null
            }
        if (violation != null) {
            throw violation
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
        val tree = boundTree(action.capabilityToken)
        validateWorkspacePath(action.path)
        val existing = tree.stat(WorkspaceTarget.Relative(action.path))
        if (existing != null && existing.kind == DocumentKind.DIRECTORY) {
            return ActionOutcome.AlreadySatisfied(
                PlatformActionOutput.DirectoryReady(metadata = existing.toMetadata()),
            )
        }
        val created = tree.ensureDirectory(action.path)
        return ActionOutcome.Applied(
            PlatformActionOutput.DirectoryReady(metadata = created.toMetadata()),
        )
    }

    private fun executeReadToExchange(action: PlatformAction.ReadToExchange): ActionOutcome {
        val tree = boundTree(action.capabilityToken)
        validateWorkspacePath(action.path)
        // Validate exchange token before any SAF I/O.
        exchange.resolveFile(action.exchangeToken)
        val handle =
            action.documentHandle?.let { documentHandle ->
                tree.openReadByHandle(action.path, documentHandle)
            } ?: tree.openRead(action.path)
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
        val tree = boundTree(action.capabilityToken)
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
        val existing = tree.stat(WorkspaceTarget.Relative(action.path))
        PlatformActionPostconditions.alreadySatisfiedWrite(action, existing)?.let { return it }
        PlatformActionPostconditions.assertWritePostcondition(action, existing)
        val written =
            tree.writeFromExchange(
                path = action.path,
                // behavior-contract: full-load-ok: complete payload required for parse/hash
                bytes = exchangeFile.readBytes(),
                mode = action.mode,
                mimeType = "application/octet-stream",
            )
        return ActionOutcome.Applied(
            PlatformActionOutput.WriteComplete(metadata = written.toMetadata()),
        )
    }

    private fun executeArtifactWrite(action: PlatformAction.ArtifactWrite): ActionOutcome {
        val tree = boundTree(action.capabilityToken)
        validateWorkspacePath(action.path)
        // The staged source lives outside the capability root by contract; its declared
        // length and digest are re-verified at the write receipt, never trusted.
        val sourceFile = java.io.File(action.source.path)
        if (!sourceFile.isFile || sourceFile.length().toULong() != action.source.length) {
            throw PlatformActionAccessException(
                category = "storage",
                code = "artifact_source_missing",
                diagnostic = "staged artifact source is missing or disagrees with the frozen length",
            )
        }
        val existing = tree.stat(WorkspaceTarget.Relative(action.path))
        PlatformActionPostconditions.classifyArtifactWrite(action, existing)?.let { return it }
        val written =
            tree.writeFromFile(
                path = action.path,
                source = sourceFile,
                mode = if (existing == null) WriteMode.CREATE else WriteMode.REPLACE,
                mimeType = "application/octet-stream",
            )
        if (written.digest != action.source.digest || written.length != action.source.length) {
            throw PlatformActionAccessException(
                category = "conflict",
                code = "write_postcondition_mismatch",
                diagnostic = "artifact write receipt differs from the declared source",
            )
        }
        return ActionOutcome.Applied(
            PlatformActionOutput.WriteComplete(metadata = written.toMetadata()),
        )
    }

    private fun executeMove(action: PlatformAction.Move): ActionOutcome {
        val tree = boundTree(action.capabilityToken)
        validateWorkspacePath(action.source)
        validateWorkspacePath(action.target)
        val source = tree.stat(WorkspaceTarget.Relative(action.source))
        val target = tree.stat(WorkspaceTarget.Relative(action.target))
        PlatformActionPostconditions.alreadySatisfiedMove(action, source, target)?.let { return it }
        PlatformActionPostconditions.assertMovePrecondition(action, source, target)
        val moved = tree.move(action.source, action.target)
        return ActionOutcome.Applied(PlatformActionOutput.MoveComplete(metadata = moved.toMetadata()))
    }

    private fun executeDelete(action: PlatformAction.Delete): ActionOutcome {
        val tree = boundTree(action.capabilityToken)
        validateWorkspacePath(action.path)
        val existing = tree.stat(WorkspaceTarget.Relative(action.path))
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
        tree.delete(action.path)
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

    private fun boundTree(token: String): BoundDocumentTree =
        when (val grant = registry.resolve(token)) {
            is SafCapabilityGrant -> SafBoundDocumentTree(documents, grant.treeUri)
            is DirectCapabilityGrant -> DirectBoundDocumentTree(directDocuments, grant)
        }
}

private interface BoundDocumentTree {
    fun stat(target: WorkspaceTarget): PlatformDocumentSnapshot?

    fun listChildren(
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage

    fun ensureDirectory(path: String): PlatformDocumentSnapshot

    fun openRead(path: String): PlatformReadHandle

    fun openReadByHandle(
        path: String,
        documentHandle: String,
    ): PlatformReadHandle

    fun writeFromExchange(
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot

    fun writeFromFile(
        path: String,
        source: java.io.File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot

    fun move(
        source: String,
        target: String,
    ): PlatformDocumentSnapshot

    fun delete(path: String)
}

private class SafBoundDocumentTree(
    private val documents: PlatformDocumentsGateway,
    private val treeUri: String,
) : BoundDocumentTree {
    override fun stat(target: WorkspaceTarget): PlatformDocumentSnapshot? = documents.stat(treeUri, target)

    override fun listChildren(
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage = documents.listChildren(treeUri, target, cursor, pageSize)

    override fun ensureDirectory(path: String): PlatformDocumentSnapshot = documents.ensureDirectory(treeUri, path)

    override fun openRead(path: String): PlatformReadHandle = documents.openRead(treeUri, path)

    override fun openReadByHandle(
        path: String,
        documentHandle: String,
    ): PlatformReadHandle = documents.openReadByHandle(treeUri, path, documentHandle)

    override fun writeFromExchange(
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot = documents.writeFromExchange(treeUri, path, bytes, mode, mimeType)

    override fun writeFromFile(
        path: String,
        source: java.io.File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot = documents.writeFromFile(treeUri, path, source, mode, mimeType)

    override fun move(
        source: String,
        target: String,
    ): PlatformDocumentSnapshot = documents.move(treeUri, source, target)

    override fun delete(path: String) = documents.delete(treeUri, path)
}

private class DirectBoundDocumentTree(
    private val documents: DirectRootDocumentsGateway,
    private val grant: DirectCapabilityGrant,
) : BoundDocumentTree {
    override fun stat(target: WorkspaceTarget): PlatformDocumentSnapshot? = documents.stat(grant, target)

    override fun listChildren(
        target: WorkspaceTarget,
        cursor: String?,
        pageSize: UInt,
    ): PlatformMetadataPage = documents.listChildren(grant, target, cursor, pageSize)

    override fun ensureDirectory(path: String): PlatformDocumentSnapshot = documents.ensureDirectory(grant, path)

    override fun openRead(path: String): PlatformReadHandle = documents.openRead(grant, path)

    override fun openReadByHandle(
        path: String,
        documentHandle: String,
    ): PlatformReadHandle = documents.openReadByHandle(grant, path, documentHandle)

    override fun writeFromExchange(
        path: String,
        bytes: ByteArray,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot = documents.writeFromExchange(grant, path, bytes, mode, mimeType)

    override fun writeFromFile(
        path: String,
        source: java.io.File,
        mode: WriteMode,
        mimeType: String?,
    ): PlatformDocumentSnapshot = documents.writeFromFile(grant, path, source, mode, mimeType)

    override fun move(
        source: String,
        target: String,
    ): PlatformDocumentSnapshot = documents.move(grant, source, target)

    override fun delete(path: String) = documents.delete(grant, path)
}

internal fun PlatformDocumentSnapshot.toMetadata(): DocumentMetadata =
    DocumentMetadata(
        target = target,
        documentHandle = documentId,
        kind = kind,
        mimeType = mimeType,
        evidence = toEvidence(),
    )

internal fun PlatformDocumentSnapshot.toEvidence(): ActionEvidence =
    ActionEvidence(
        length = length,
        digest =
            digest?.let { ContentDigest.Verified(it) } ?: ContentDigest.Unknown,
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

internal fun isValidWorkspacePath(path: String): Boolean {
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

internal fun postconditionMismatch(diagnostic: String): PlatformActionAccessException =
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

internal class PlatformActionAccessException(
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
