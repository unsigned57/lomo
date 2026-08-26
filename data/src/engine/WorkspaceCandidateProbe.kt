package com.lomo.data.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.source.isContentStorageUri
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.WorkspaceCandidateValidator
import java.io.File
import java.util.Locale

/**
 * Production candidate probe used before freeze + durable root persistence.
 *
 * Direct roots must exist as writable directories. SAF roots must resolve via DocumentFile,
 * report canWrite, and hold a matching persisted read+write tree grant. Blank-only validation is
 * intentionally insufficient here.
 */
class WorkspaceCandidateProbe(
    private val context: Context,
    private val isContentUri: (String) -> Boolean = ::isContentStorageUri,
) : WorkspaceCandidateValidator {
    override suspend fun validate(location: StorageLocation) {
        val raw = location.raw.trim()
        require(raw.isNotEmpty()) { "Candidate workspace location must be non-blank" }
        if (isContentUri(raw)) {
            validateSaf(raw)
        } else {
            validateDirect(raw)
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
        failureCode?.let { throw WorkspaceCandidateValidationException(it, path) }
    }

    private fun validateSaf(uriString: String) {
        val uri = uriString.toUri()
        val root = DocumentFile.fromTreeUri(context, uri)
        val failureCode = when {
            root == null -> "saf_root_unavailable"
            !root.exists() || !root.isDirectory -> "workspace_content_unavailable"
            !root.canWrite() -> "saf_root_unwritable"
            else -> null
        }
        failureCode?.let { throw WorkspaceCandidateValidationException(it, uriString) }
        checkNotNull(root)
        val granted =
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission &&
                    permission.isWritePermission &&
                    uriTreesMatch(permission.uri, uri.toString())
            }
        if (!granted) throw WorkspaceCandidateValidationException("saf_grant_revoked", uriString)
    }
}

class WorkspaceCandidateValidationException(
    val code: String,
    location: String,
) : IllegalStateException("$code: workspace candidate rejected ($location)")

/**
 * Grant matching for tree URIs: exact string equality is too brittle across encoding/normalization,
 * so compare normalized tree document ids when both are tree URIs.
 */
internal fun uriTreesMatch(
    granted: Uri,
    candidate: String,
): Boolean {
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
