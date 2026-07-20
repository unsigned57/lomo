package com.lomo.data.engine

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.source.isContentStorageUri
import com.lomo.domain.model.StorageLocation
import com.lomo.domain.repository.WorkspaceCandidateValidator
import java.io.File

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
        check(root.exists() && root.isDirectory) {
            "Candidate workspace path is not an existing directory: $path"
        }
        check(root.canRead()) {
            "Candidate workspace path is not readable: $path"
        }
        check(root.canWrite()) {
            "Candidate workspace path is not writable: $path"
        }
    }

    private fun validateSaf(uriString: String) {
        val uri = uriString.toUri()
        val root =
            DocumentFile.fromTreeUri(context, uri)
                ?: error("Candidate SAF tree URI is not resolvable: $uriString")
        check(root.exists() && root.isDirectory) {
            "Candidate SAF tree does not exist or is not a directory: $uriString"
        }
        check(root.canWrite()) {
            "Candidate SAF tree is not writable: $uriString"
        }
        val granted =
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission &&
                    permission.isWritePermission &&
                    uriTreesMatch(permission.uri, uri)
            }
        check(granted) {
            "Candidate SAF tree has no persisted read+write grant: $uriString"
        }
    }
}

/**
 * Grant matching for tree URIs: exact string equality is too brittle across encoding/normalization,
 * so compare normalized tree document ids when both are tree URIs.
 */
internal fun uriTreesMatch(
    granted: Uri,
    candidate: Uri,
): Boolean {
    if (granted == candidate) return true
    if (granted.toString() == candidate.toString()) return true
    val grantedTree = granted.treeDocumentIdOrNull() ?: return false
    val candidateTree = candidate.treeDocumentIdOrNull() ?: return false
    return granted.authority == candidate.authority && grantedTree == candidateTree
}

private fun Uri.treeDocumentIdOrNull(): String? =
    try {
        android.provider.DocumentsContract.getTreeDocumentId(this)
    } catch (_: IllegalArgumentException) {
        // Not a documents tree URI; grant matching falls back to exact/string equality.
        null
    }
