package com.lomo.data.git

import com.lomo.data.util.runNonFatalCatching
import com.lomo.domain.model.CredentialField
import com.lomo.domain.model.GitSyncErrorCode
import com.lomo.domain.model.GitSyncFailureException
import com.lomo.domain.repository.CredentialRepository
import com.lomo.domain.model.CredentialSecretReadResult
import com.lomo.domain.repository.SecuritySessionPolicy
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitCredentialStrategy
    @Inject
    constructor(
        private val credentialRepository: CredentialRepository,
        private val securitySessionPolicy: SecuritySessionPolicy,
    ) {
        @Volatile
        private var cachedCredentialIndex: Int = -1

        suspend fun credentialProviders(): List<UsernamePasswordCredentialsProvider> {
            val tokenRead =
                credentialRepository
                    .readSecret(
                        field = CredentialField.GIT_TOKEN,
                        authorization = securitySessionPolicy.authorizeCredentialRead(),
                    ).toGitTokenRead()
            if (tokenRead is GitTokenRead.Failure) {
                throw tokenRead.error
            }
            val token = (tokenRead as GitTokenRead.Present).value.trim()
            if (token.isBlank()) {
                throw gitPatRequiredFailure()
            }

            return listOf(
                UsernamePasswordCredentialsProvider("x-access-token", token),
                UsernamePasswordCredentialsProvider(token, ""),
                UsernamePasswordCredentialsProvider(token, "x-oauth-basic"),
            )
        }

        fun <T> runWithCredentialFallback(
            providers: List<UsernamePasswordCredentialsProvider>,
            operation: String,
            block: (UsernamePasswordCredentialsProvider) -> T,
        ): T {
            val cached = cachedCredentialIndex
            if (cached in providers.indices) {
                val attempt = runNonFatalCatching { block(providers[cached]) }
                if (attempt.isSuccess) {
                    return attempt.getOrThrow()
                }
                attempt.exceptionOrNull()?.let { error ->
                    Timber.w(error, "$operation failed with cached credential strategy #${cached + 1}, resetting cache")
                    cachedCredentialIndex = -1
                }
            }

            var lastError: Throwable? = null
            providers.forEachIndexed { index, provider ->
                if (index == cached) return@forEachIndexed
                val attempt = runNonFatalCatching { block(provider) }
                if (attempt.isSuccess) {
                    cachedCredentialIndex = index
                    return attempt.getOrThrow()
                }
                attempt.exceptionOrNull()?.let { error ->
                    lastError = error
                    Timber.w(error, "$operation failed with credential strategy #${index + 1}")
                }
            }

            throw lastError ?: IllegalStateException("$operation failed without a captured exception")
        }
    }

private sealed interface GitTokenRead {
    data class Present(
        val value: String,
    ) : GitTokenRead

    data class Failure(
        val error: GitSyncFailureException,
    ) : GitTokenRead
}

private fun CredentialSecretReadResult.toGitTokenRead(): GitTokenRead =
    when (this) {
        CredentialSecretReadResult.Missing -> GitTokenRead.Failure(gitPatRequiredFailure())
        is CredentialSecretReadResult.Present -> GitTokenRead.Present(value)
        CredentialSecretReadResult.Unreadable ->
            GitTokenRead.Failure(
                GitSyncFailureException(
                    code = GitSyncErrorCode.CREDENTIAL_UNREADABLE,
                    message = "Git credential GIT_TOKEN is unreadable",
                ),
            )
        is CredentialSecretReadResult.Unauthorized ->
            GitTokenRead.Failure(
                GitSyncFailureException(
                    code = GitSyncErrorCode.CREDENTIAL_UNAUTHORIZED,
                    message = "Git credential read denied: $reason",
                ),
            )
    }

private fun gitPatRequiredFailure(): GitSyncFailureException =
    GitSyncFailureException(
        code = GitSyncErrorCode.PAT_REQUIRED,
        message = GitSyncErrorMessages.PAT_REQUIRED,
    )
