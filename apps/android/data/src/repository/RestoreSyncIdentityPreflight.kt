package com.lomo.data.repository

import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.worker.GIT_AUTHOR_EMAIL_DEFAULT
import com.lomo.data.worker.GIT_AUTHOR_NAME_DEFAULT
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SecuritySessionPolicy
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Canonical-identity diff for a bulk settings restore. Restored values bypass the per-field
 * mutation repositories, so this replays the same normalization `RustSyncCycleInputFactory`
 * applies and compares the *selected* backend's identity inputs before vs. after. A
 * backend-type switch always moves the durable fence (`kind=` is part of canonical identity);
 * a restore that leaves the selected backend and all its identity inputs untouched is a
 * durable-state no-op.
 */
internal class RestoreSyncIdentityPreflight(
    private val dataStore: LomoDataStore,
    private val credentialRepository: CredentialRepository,
    private val securitySessionPolicy: SecuritySessionPolicy,
) {
    suspend fun movesSyncIdentity(
        snapshot: MigrationSettingsSnapshot,
        sensitiveSettings: Map<String, String>,
        ordinaryPlan: OrdinarySettingsRestorePlan,
    ): Boolean {
        val backendBefore = dataStore.syncBackendType.first()
        val backendAfter =
            ordinaryAfter(ordinaryPlan, snapshot, SettingsKey.SYNC_BACKEND_TYPE, backendBefore)
        if (backendBefore.canonicalBackendKind() != backendAfter.canonicalBackendKind()) {
            return true
        }
        return when (backendAfter.canonicalBackendKind()) {
            "git" -> gitIdentityMoved(snapshot, sensitiveSettings, ordinaryPlan)
            "webdav" -> webDavIdentityMoved(snapshot, sensitiveSettings, ordinaryPlan)
            "s3" -> s3IdentityMoved(snapshot, sensitiveSettings, ordinaryPlan)
            else -> false
        }
    }

    /** Post-restore effective value: `Restore` writes the payload verbatim (absent nullable
     * keys are removed → empty); `Skip` leaves the stored value untouched. */
    private fun ordinaryAfter(
        plan: OrdinarySettingsRestorePlan,
        snapshot: MigrationSettingsSnapshot,
        key: String,
        before: String?,
    ): String =
        when (plan) {
            is OrdinarySettingsRestorePlan.Restore -> snapshot.preferences[key].orEmpty()
            is OrdinarySettingsRestorePlan.Skip -> before.orEmpty()
        }

    /** Post-restore effective credential: absent keys are cleared by the restore's omission-clear. */
    private fun sensitiveAfter(
        sensitiveSettings: Map<String, String>,
        field: CredentialField,
    ): String = sensitiveSettings[field.migrationSensitiveKey()].orEmpty()

    /**
     * The stored credential's comparison baseline. [CredentialSecretReadResult.Present] and
     * [CredentialSecretReadResult.Missing] are *known* states (the stored value, or definitely
     * empty). [CredentialSecretReadResult.Unreadable] and [CredentialSecretReadResult.Unauthorized]
     * are *undetermined*: the store cannot prove the secret equals anything — including empty —
     * so it can never certify "unchanged".
     */
    private sealed interface SensitiveBaseline {
        data class Known(
            val value: String,
        ) : SensitiveBaseline

        data object Undetermined : SensitiveBaseline
    }

    private suspend fun sensitiveBaseline(field: CredentialField): SensitiveBaseline =
        when (
            val result =
                credentialRepository.readSecret(
                    field = field,
                    authorization = securitySessionPolicy.authorizeCredentialRead(),
                )
        ) {
            is CredentialSecretReadResult.Present -> SensitiveBaseline.Known(result.value)
            CredentialSecretReadResult.Missing -> SensitiveBaseline.Known("")
            CredentialSecretReadResult.Unreadable,
            is CredentialSecretReadResult.Unauthorized,
            -> SensitiveBaseline.Undetermined
        }

    /**
     * True when the restore changes the stored credential's identity contribution. An
     * undetermined baseline always moves: if we cannot prove the stored secret already equals
     * the post-restore value, the stale fence must be disposed before the clear/import lands.
     */
    private fun sensitiveMoved(
        after: String,
        baseline: SensitiveBaseline,
    ): Boolean =
        when (baseline) {
            is SensitiveBaseline.Known -> after != baseline.value
            SensitiveBaseline.Undetermined -> true
        }

    private suspend fun gitIdentityMoved(
        snapshot: MigrationSettingsSnapshot,
        sensitiveSettings: Map<String, String>,
        plan: OrdinarySettingsRestorePlan,
    ): Boolean {
        val remoteBefore = dataStore.gitRemoteUrl.first()
        val nameBefore = dataStore.gitAuthorName.first()
        val emailBefore = dataStore.gitAuthorEmail.first()
        return ordinaryAfter(plan, snapshot, SettingsKey.GIT_REMOTE_URL, remoteBefore).trim() !=
            remoteBefore?.trim().orEmpty() ||
            ordinaryAfter(plan, snapshot, SettingsKey.GIT_AUTHOR_NAME, nameBefore).trim()
                .ifBlank { GIT_AUTHOR_NAME_DEFAULT } !=
            nameBefore.trim().ifBlank { GIT_AUTHOR_NAME_DEFAULT } ||
            ordinaryAfter(plan, snapshot, SettingsKey.GIT_AUTHOR_EMAIL, emailBefore).trim()
                .ifBlank { GIT_AUTHOR_EMAIL_DEFAULT } !=
            emailBefore.trim().ifBlank { GIT_AUTHOR_EMAIL_DEFAULT } ||
            sensitiveMoved(
                sensitiveAfter(sensitiveSettings, CredentialField.GIT_USERNAME),
                sensitiveBaseline(CredentialField.GIT_USERNAME),
            )
    }

    private suspend fun webDavIdentityMoved(
        snapshot: MigrationSettingsSnapshot,
        sensitiveSettings: Map<String, String>,
        plan: OrdinarySettingsRestorePlan,
    ): Boolean {
        val endpointBefore = dataStore.webDavEndpointUrl.first()
        val baseBefore = dataStore.webDavBaseUrl.first()
        // Resolved endpoint mirrors the factory/mutation repo: endpointUrl wins, baseUrl
        // is the fallback — a shadowed baseUrl change never moves the identity.
        val resolvedBefore = endpointBefore?.trim().orEmpty().ifBlank { baseBefore?.trim().orEmpty() }
        val endpointAfter = ordinaryAfter(plan, snapshot, SettingsKey.WEBDAV_ENDPOINT_URL, endpointBefore)
        val baseAfter = ordinaryAfter(plan, snapshot, SettingsKey.WEBDAV_BASE_URL, baseBefore)
        val resolvedAfter = endpointAfter.trim().ifBlank { baseAfter.trim() }
        return resolvedAfter != resolvedBefore ||
            sensitiveMoved(
                sensitiveAfter(sensitiveSettings, CredentialField.WEBDAV_USERNAME),
                sensitiveBaseline(CredentialField.WEBDAV_USERNAME),
            )
    }

    private suspend fun s3IdentityMoved(
        snapshot: MigrationSettingsSnapshot,
        sensitiveSettings: Map<String, String>,
        plan: OrdinarySettingsRestorePlan,
    ): Boolean {
        val endpointBefore = dataStore.s3EndpointUrl.first()
        val regionBefore = dataStore.s3Region.first()
        val bucketBefore = dataStore.s3Bucket.first()
        val prefixBefore = dataStore.s3Prefix.first()
        return ordinaryAfter(plan, snapshot, SettingsKey.S3_ENDPOINT_URL, endpointBefore).trim() !=
            endpointBefore?.trim().orEmpty() ||
            ordinaryAfter(plan, snapshot, SettingsKey.S3_REGION, regionBefore).trim() !=
            regionBefore?.trim().orEmpty() ||
            ordinaryAfter(plan, snapshot, SettingsKey.S3_BUCKET, bucketBefore).trim() !=
            bucketBefore?.trim().orEmpty() ||
            // The mutation repository persists prefix as trim()+trim('/') — normalize both
            // sides so a raw payload spelling does not look like an identity move.
            ordinaryAfter(plan, snapshot, SettingsKey.S3_PREFIX, prefixBefore).trim().trim('/') !=
            prefixBefore.orEmpty().trim().trim('/') ||
            sensitiveMoved(
                sensitiveAfter(sensitiveSettings, CredentialField.S3_ACCESS_KEY_ID),
                sensitiveBaseline(CredentialField.S3_ACCESS_KEY_ID),
            )
    }

    private fun String.canonicalBackendKind(): String = trim().lowercase(Locale.ROOT)
}
