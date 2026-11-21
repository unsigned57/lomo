package com.lomo.data.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.source.isContentStorageUri
import com.lomo.domain.model.EngineCommandFailureException
import com.lomo.domain.model.EngineFailureCategory
import com.lomo.domain.model.EngineRetryDisposition
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.model.WorkspaceFailureStage
import com.lomo.domain.repository.WorkspaceCandidateValidator
import com.lomo.domain.usecase.DispatcherProvider
import com.lomo.nativebridge.WorkspaceTarget
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Production candidate probe used before freeze + durable root persistence.
 *
 * Direct roots must exist as writable directories. SAF roots must hold a persisted read/write
 * grant, resolve the selected document and provide a complete directory observation. Blank-only validation is
 * intentionally insufficient here.
 */
class WorkspaceCandidateProbe(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val isContentUri: (String) -> Boolean = ::isContentStorageUri,
) : WorkspaceCandidateValidator {
    override suspend fun validate(location: StorageLocation) = withContext(dispatcherProvider.io) {
        val raw = location.raw.trim()
        require(raw.isNotEmpty()) { "Candidate workspace location must be non-blank" }
        try {
            if (isContentUri(raw)) {
                validateSaf(raw)
            } else {
                validateDirect(raw)
            }
        } catch (error: SecurityException) {
            throw candidateFailure("workspace_access_denied", EngineFailureCategory.PERMISSION, error)
        } catch (error: PlatformActionAccessException) {
            throw EngineCommandFailureException(
                error.toFailure().toEngineCommandFailure().copy(workspaceStage = WorkspaceFailureStage.VALIDATE),
                cause = error,
            )
        }
    }

    private fun validateDirect(path: String) {
        val root = File(path)
        val failureCode = when {
            !root.exists() || !root.isDirectory -> "workspace_root_unavailable"
            !root.canRead() -> "workspace_root_unreadable"
            !root.canWrite() -> "workspace_root_unwritable"
            else -> null
        }
        failureCode?.let { throw candidateFailure(it, EngineFailureCategory.STORAGE) }
    }

    private fun validateSaf(uriString: String) {
        val uri = uriString.toUri()
        val granted =
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission &&
                    permission.isWritePermission &&
                    uriTreesMatch(permission.uri, uri.toString())
            }
        if (!granted) throw candidateFailure("saf_grant_revoked", EngineFailureCategory.PERMISSION)
        val documentId = selectedSafDocumentId(uriString, uri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        val root = DocumentFile.fromSingleUri(context, documentUri)
        val failureCode = when {
            root == null -> "saf_root_unavailable"
            !root.exists() || !root.isDirectory -> "workspace_content_unavailable"
            !root.canRead() -> "saf_root_unreadable"
            !root.canWrite() -> "saf_root_unwritable"
            else -> null
        }
        failureCode?.let { throw candidateFailure(it, EngineFailureCategory.STORAGE) }
        validateSafEnumeration(uriString)
    }

    private fun validateSafEnumeration(uriString: String) {
        val documents = ContentResolverPlatformDocumentsGateway(context.contentResolver)
        var cursor: String? = null
        do {
            val page = documents.listChildren(uriString, WorkspaceTarget.Root, cursor, 256u)
            if (page.incomplete) {
                throw candidateFailure("metadata_enumeration_incomplete", EngineFailureCategory.STORAGE)
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }
}

private fun candidateFailure(code: String, category: EngineFailureCategory, cause: Throwable? = null) =
    engineCommandFailure(
        category = category,
        code = code,
        retryDisposition = EngineRetryDisposition.AFTER_USER_ACTION,
        diagnostic = "The workspace candidate could not be validated",
        stage = WorkspaceFailureStage.VALIDATE,
        cause = cause,
    )

/**
 * Grant matching for tree URIs: exact string equality is too brittle across encoding/normalization,
 * so compare normalized tree document ids when both are tree URIs.
 */
internal fun uriTreesMatch(granted: Uri, candidate: String): Boolean {
    val grantedRaw = granted.toString()
    if (grantedRaw == candidate) return true
    val grantedTree = grantedRaw.treeDocumentIdOrNull() ?: return false
    val candidateTree = candidate.treeDocumentIdOrNull() ?: return false
    val grantedAuthority = grantedRaw.uriAuthorityOrNull()
    val candidateAuthority = candidate.uriAuthorityOrNull()
    return grantedAuthority == candidateAuthority && grantedTree == candidateTree
}

private fun String.treeDocumentIdOrNull(): String? {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val treeIndex = withoutQuery.indexOf("/tree/")
    if (treeIndex < 0) return null
    val afterTree = withoutQuery.substring(treeIndex + "/tree/".length)
    if (afterTree.isEmpty()) return null
    val rawId =
        if (afterTree.contains("/document/")) {
            afterTree.substringBefore("/document/")
        } else {
            afterTree
        }
    return runCatching {
        java.net.URLDecoder.decode(rawId.replace("+", "%2B"), "UTF-8")
    }.fold(
        onSuccess = { decoded -> decoded.takeIf(String::isNotEmpty) },
        onFailure = { null },
    )
}

private fun String.uriAuthorityOrNull(): String? {
    if (!startsWith("content://", ignoreCase = true)) return null
    val withoutScheme = substring("content://".length)
    val authority = withoutScheme.substringBefore('/')
    return authority.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
}
