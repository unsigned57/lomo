package com.lomo.data.sync

import com.lomo.data.engine.media.WorkspaceFilesystemRoot
import com.lomo.data.local.datastore.LomoDataStore
import com.lomo.data.worker.DeferredLockWorkStore
import com.lomo.data.worker.RustSyncScheduler
import com.lomo.domain.model.CredentialField
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.repository.SyncStateResetRepository
import com.lomo.domain.usecase.DefaultDispatcherProvider
import com.lomo.domain.usecase.DispatcherProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * One-time migration for legacy Git remote URLs that embed `userinfo` credentials.
 *
 * Before the endpoint boundary hardened, a persisted remote could carry `user:pass@host`. The
 * credential material moves into the Keystore-backed credential store, the sanitized URL is
 * persisted back, and every surface that journaled the poisoned endpoint — queued
 * WorkManager inputs, the deferred lock work blob, the durable sync control tree, and the
 * app-private git mirror config — is purged so no plaintext secret remains on disk.
 *
 * An endpoint that cannot be parsed safely (SCP-like SSH, empty authority) is not trusted:
 * the remote is dropped so execution refuses until the user reconfigures. The migration is
 * idempotent; a clean endpoint is a no-op.
 */
internal class GitEndpointSecurityMigration(
    private val dataStore: LomoDataStore,
    private val credentialRepository: CredentialRepository,
    private val scheduler: RustSyncScheduler,
    private val deferredLockStore: DeferredLockWorkStore,
    private val syncStateReset: SyncStateResetRepository,
    private val workspaceRoot: WorkspaceFilesystemRoot,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
) {
    suspend fun migrateIfNeeded() {
        val remote = dataStore.gitRemoteUrl.first()?.trim().orEmpty()
        if (remote.isEmpty()) {
            return
        }
        when (val parsed = LegacyGitUserinfoEndpoint.parse(remote)) {
            null -> Unit
            is LegacyGitUserinfoEndpoint.Trusted -> migrateTrusted(remote, parsed)
            LegacyGitUserinfoEndpoint.Untrusted -> refuseUntrusted(remote)
        }
    }

    private suspend fun migrateTrusted(
        remote: String,
        parsed: LegacyGitUserinfoEndpoint.Trusted,
    ) {
        // Credentials land first: a crash between writes leaves the poisoned URL in place,
        // so the next pass re-extracts instead of losing the secret.
        parsed.password?.let { credentialRepository.writeSecret(CredentialField.GIT_TOKEN, it) }
        parsed.username?.let { credentialRepository.writeSecret(CredentialField.GIT_USERNAME, it) }
        dataStore.updateGitRemoteUrl(parsed.sanitizedUrl)
        purgePoisonedSurfaces()
        Timber.w(
            "Git remote userinfo migrated to credential store (url=%s)",
            redactUserinfo(remote),
        )
    }

    private suspend fun refuseUntrusted(remote: String) {
        dataStore.updateGitRemoteUrl(null)
        purgePoisonedSurfaces()
        Timber.w(
            "Git remote dropped: untrusted legacy endpoint needs reconfiguration (url=%s)",
            redactUserinfo(remote),
        )
    }

    private suspend fun purgePoisonedSurfaces() {
        scheduler.cancel()
        deferredLockStore.clear()
        syncStateReset.resetWorkspaceScopedSyncState()
        val root = workspaceRoot.absolutePathOrNull()
        if (!root.isNullOrBlank()) {
            withContext(dispatcherProvider.io) {
                File(root, GIT_MIRROR_RELATIVE_PATH).deleteRecursively()
            }
        }
    }

    private companion object {
        private const val GIT_MIRROR_RELATIVE_PATH = ".lomo/sync/v1/git-mirror"

        private fun redactUserinfo(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) {
                return "<redacted>"
            }
            val authorityStart = schemeEnd + 3
            val authorityEnd =
                url.indexOf('/', authorityStart).takeIf { it >= 0 } ?: url.length
            val authority = url.substring(authorityStart, authorityEnd)
            val at = authority.lastIndexOf('@')
            if (at < 0) {
                return url
            }
            return url.substring(0, authorityStart) + "***@" + url.substring(authorityEnd)
        }
    }
}

/**
 * Parses a persisted remote URL for embedded userinfo.
 *
 * `null` means the endpoint carries no userinfo. [Trusted] carries the extracted
 * username/password (already percent-decoded) and the sanitized URL; [Untrusted] means the
 * endpoint is not safely parseable (SCP-like SSH or a broken authority) and must not keep
 * running.
 */
internal sealed interface LegacyGitUserinfoEndpoint {
    data class Trusted(
        val username: String?,
        val password: String?,
        val sanitizedUrl: String,
    ) : LegacyGitUserinfoEndpoint

    data object Untrusted : LegacyGitUserinfoEndpoint

    companion object {
        private const val PERCENT_TRIPLET_LENGTH = 3

        fun parse(remote: String): LegacyGitUserinfoEndpoint? {
            val schemeEnd = remote.indexOf("://")
            if (schemeEnd < 0) {
                // SCP-like `user@host:path` is SSH transport with an embedded identity —
                // nothing here is a reusable HTTPS credential.
                return if (isScpLike(remote)) Untrusted else null
            }
            val authorityStart = schemeEnd + 3
            val authorityEnd =
                remote.indexOf('/', authorityStart).takeIf { it >= 0 } ?: remote.length
            val authority = remote.substring(authorityStart, authorityEnd)
            val at = authority.lastIndexOf('@')
            if (at < 0) {
                return null
            }
            val hostport = authority.substring(at + 1)
            if (hostport.isBlank()) {
                return Untrusted
            }
            val userinfo = authority.substring(0, at)
            val usernameRaw = userinfo.substringBefore(':')
            val passwordRaw = userinfo.substringAfter(':', "")
            val username = decodePercent(usernameRaw) ?: return Untrusted
            val password =
                if (userinfo.contains(':')) {
                    decodePercent(passwordRaw) ?: return Untrusted
                } else {
                    null
                }
            val sanitized =
                remote.substring(0, authorityStart) + hostport + remote.substring(authorityEnd)
            return Trusted(
                username = username.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                sanitizedUrl = sanitized,
            )
        }

        private fun isScpLike(remote: String): Boolean {
            val beforeColon = remote.substringBefore(':', "")
            return beforeColon.contains('@') && !beforeColon.contains('/')
        }

        /** RFC 3986 percent-decoding; `+` stays literal (userinfo is not form data). */
        private fun decodePercent(value: String): String? {
            if (!value.contains('%')) {
                return value
            }
            val input = value.toByteArray(Charsets.UTF_8)
            val bytes = java.io.ByteArrayOutputStream(input.size)
            var index = 0
            while (index < input.size) {
                val byte = input[index]
                if (byte == '%'.code.toByte()) {
                    if (index + 2 >= input.size) {
                        return null
                    }
                    val hex = String(input, index + 1, 2, Charsets.US_ASCII)
                    val decoded = hex.toIntOrNull(16) ?: return null
                    bytes.write(decoded)
                    index += PERCENT_TRIPLET_LENGTH
                } else {
                    bytes.write(byte.toInt())
                    index += 1
                }
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
