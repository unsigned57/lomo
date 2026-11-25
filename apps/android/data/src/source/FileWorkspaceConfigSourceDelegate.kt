package com.lomo.data.source

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.lomo.data.engine.buildTreeDocumentUri
import com.lomo.data.engine.uriTreesMatch
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.domain.model.canonicalCrossUserAuthorityOrNull
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

class FileWorkspaceConfigSourceDelegate(
    private val context: Context,
    private val dataStore: LomoDataStore,
    private val backendResolver: FileStorageBackendResolver,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) : WorkspaceConfigSource {
    override suspend fun setRoot(type: StorageRootType, pathOrUri: String) {
        releaseOrphanedTreePermissions(pathOrUri)
        dataStore.updateStorageRoot(type, pathOrUri)
    }

    override fun getRootFlow(type: StorageRootType): Flow<String?> =
        dataStore.storageRootConfigFlow(type).map(StorageRootConfig::preferredLocation)

    override fun getRootDisplayNameFlow(type: StorageRootType): Flow<String?> = getRootFlow(type).map { uriOrPath ->
        when {
            uriOrPath == null -> null

            isContentStorageUri(uriOrPath) ->
                withContext(dispatcherProvider.io) {
                    displayNameForUri(uriOrPath, uriOrPath.toUri())
                }

            else -> uriOrPath
        }
    }

    override suspend fun createDirectory(name: String): String =
        backendResolver.workspaceBackend()?.createDirectory(name)
            ?: throw IOException("No storage configured")

    private fun displayNameForUri(rawUri: String, uri: Uri): String = try {
        val selected = when {
            "/document/" in rawUri || DocumentsContract.isDocumentUri(context, uri) ->
                DocumentFile.fromSingleUri(context, uri)

            canonicalCrossUserAuthorityOrNull(rawUri) != null ->
                DocumentFile.fromSingleUri(
                    context,
                    buildTreeDocumentUri(rawUri, uri, DocumentsContract.getTreeDocumentId(uri)),
                )

            else ->
                DocumentFile.fromTreeUri(context, uri)
        }
        selected?.name ?: uri.lastPathSegment ?: uri.toString()
    } catch (_: Exception) {
        uri.lastPathSegment ?: uri.toString()
    }

    /**
     * Releases persisted SAF tree permissions that no longer back any configured storage slot.
     *
     * Re-picking a folder leaves the previous folder's grant persisted. Without pruning, repeated
     * re-picks accumulate grants and can push the app past the system's persisted-permission cap,
     * after which Android silently evicts the oldest grant — potentially the active workspace root,
     * so writes start failing. Only grants absent from every configured slot are released, so an
     * in-use folder (including ones shared across slots) is never affected.
     */
    private suspend fun releaseOrphanedTreePermissions(candidate: String) {
        // A selected notes tree owns its grant during preparation/indexing, before the
        // committed root changes. Configuring sibling media roots must not revoke it.
        val transition = dataStore.pendingRootTransition()
        val activeUris =
            setOfNotNull(
                candidate,
                dataStore.rootUri.first(),
                dataStore.imageUri.first(),
                dataStore.voiceUri.first(),
                dataStore.syncInboxUri.first(),
                dataStore.s3LocalSyncDirectory.first(),
                transition?.previous?.raw,
                transition?.let { it.candidate.raw },
                transition?.mediaChange?.previous?.raw,
                transition?.mediaChange?.candidate?.raw,
            ).map(String::trim).filter { it.isNotEmpty() && isContentStorageUri(it) }.toSet()

        val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.persistedUriPermissions.forEach { permission ->
            // behavior-contract: nested-scan-ok: SAF grants and workspace URIs are a bounded catalog set
            if (activeUris.any { active ->
                    active == permission.uri.toString() ||
                        try {
                            uriTreesMatch(permission.uri, active)
                        } catch (_: IllegalArgumentException) {
                            // behavior-contract: silent-result-ok: malformed stored URI is not an active grant
                            false
                        }
                }
            ) {
                return@forEach
            }
            // behavior-contract: silent-result-ok: a grant we no longer hold is already gone
            runCatching {
                context.contentResolver.releasePersistableUriPermission(permission.uri, releaseFlags)
            }
        }
    }
}
