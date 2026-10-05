package com.lomo.data.worker

import androidx.work.Data
import com.lomo.data.engine.sync.SecretMaterialSource
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.sync.LegacyGitUserinfoEndpoint
import com.lomo.domain.model.CredentialProvider
import com.lomo.domain.model.SyncBackendType
import com.lomo.domain.model.identityField
import com.lomo.domain.model.secretField
import kotlinx.coroutines.flow.first

/**
 * Derives the non-secret WorkManager input for one remote sync cycle from persisted config.
 *
 * Fails closed (`null`) on incomplete or unsafe configuration — the caller cancels scheduled
 * work instead of enqueueing unusable input. A Git endpoint still carrying URL userinfo is
 * refused here as well: [com.lomo.data.sync.GitEndpointSecurityMigration] owns the one-time
 * cleanup, and this boundary never lets a poisoned endpoint reach WorkManager again.
 */
internal class RustSyncCycleInputFactory(
    private val dataStore: LomoDataStore,
    private val identityMaterial: SecretMaterialSource,
) {
    suspend fun resolveCycleInput(
        backend: SyncBackendType,
        root: String,
        secretFieldKeyOverride: String? = null,
    ): Data? =
        when (backend) {
            SyncBackendType.WEBDAV -> webDavInput(root, secretFieldKeyOverride)
            SyncBackendType.S3 -> s3Input(root, secretFieldKeyOverride)
            SyncBackendType.GIT -> gitInput(root, secretFieldKeyOverride)
            SyncBackendType.NONE,
            SyncBackendType.INBOX,
            SyncBackendType.UNKNOWN,
            -> null
        }

    private suspend fun webDavInput(
        root: String,
        secretFieldKeyOverride: String?,
    ): Data? {
        val endpoint =
            dataStore.webDavEndpointUrl.first()?.trim().orEmpty().ifBlank {
                dataStore.webDavBaseUrl.first()?.trim().orEmpty()
            }
        val provider = CredentialProvider.WEBDAV
        val identityField = provider.identityField()
        if (endpoint.isBlank() ||
            identityField == null ||
            !identityMaterial.hasMaterial(identityField.name)
        ) {
            return null
        }
        return RustSyncWorker.inputData(
            workspaceRoot = root,
            backendKind = "webdav",
            endpointUrl = endpoint,
            identityFieldKey = identityField.name,
            remoteDatasetId = datasetId("webdav", endpoint, ""),
            secretFieldKey = secretFieldKeyOverride ?: provider.secretField().name,
            applyRemote = true,
        )
    }

    private suspend fun s3Input(
        root: String,
        secretFieldKeyOverride: String?,
    ): Data? {
        val endpoint = dataStore.s3EndpointUrl.first()?.trim().orEmpty()
        val region = dataStore.s3Region.first()?.trim().orEmpty()
        val bucket = dataStore.s3Bucket.first()?.trim().orEmpty()
        val prefix = dataStore.s3Prefix.first()?.trim().orEmpty()
        val provider = CredentialProvider.S3
        val identityField = provider.identityField()
        val identityReady =
            identityField != null && identityMaterial.hasMaterial(identityField.name)
        if (endpoint.isBlank() || region.isBlank() || bucket.isBlank() || !identityReady) {
            return null
        }
        return RustSyncWorker.inputData(
            workspaceRoot = root,
            backendKind = "s3",
            endpointUrl = endpoint,
            s3Bucket = bucket,
            s3Prefix = prefix,
            s3Region = region,
            identityFieldKey = identityField.name,
            remoteDatasetId = datasetId("s3", endpoint, bucket),
            secretFieldKey = secretFieldKeyOverride ?: provider.secretField().name,
            applyRemote = true,
        )
    }

    private suspend fun gitInput(
        root: String,
        secretFieldKeyOverride: String?,
    ): Data? {
        val remote = dataStore.gitRemoteUrl.first()?.trim().orEmpty()
        val branch = dataStore.gitBranch.first().trim()
        if (remote.isBlank() ||
            branch.isBlank() ||
            LegacyGitUserinfoEndpoint.parse(remote) != null
        ) {
            return null
        }
        val authorName = dataStore.gitAuthorName.first().trim().ifBlank { GIT_AUTHOR_NAME_DEFAULT }
        val authorEmail =
            dataStore.gitAuthorEmail
                .first()
                .trim()
                .ifBlank { GIT_AUTHOR_EMAIL_DEFAULT }
        val provider = CredentialProvider.GIT
        val identityFieldKey =
            provider.identityField()?.run {
                name.takeIf { identityMaterial.hasMaterial(it) }
            }
        return RustSyncWorker.inputData(
            workspaceRoot = root,
            backendKind = "git",
            endpointUrl = remote,
            gitBranch = branch,
            gitAuthorName = authorName,
            gitAuthorEmail = authorEmail,
            remoteDatasetId = datasetId("git", remote, ""),
            identityFieldKey = identityFieldKey,
            secretFieldKey = secretFieldKeyOverride ?: provider.secretField().name,
            applyRemote = true,
        )
    }
}

/** Git author fallbacks applied when the stored value is blank — part of canonical identity. */
internal const val GIT_AUTHOR_NAME_DEFAULT: String = "Lomo"
internal const val GIT_AUTHOR_EMAIL_DEFAULT: String = "git@lomo.local"

internal const val DATASET_ID_MAX_LEN: Int = 128

internal fun datasetId(
    backend: String,
    endpoint: String,
    bucket: String,
): String {
    val raw = "$backend|$endpoint|$bucket"
    return raw.take(DATASET_ID_MAX_LEN).ifBlank { backend }
}

