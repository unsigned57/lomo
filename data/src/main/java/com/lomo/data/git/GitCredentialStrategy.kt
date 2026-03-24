package com.lomo.data.git

import com.lomo.data.util.runNonFatalCatching
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitCredentialStrategy
    @Inject
    constructor(
        private val credentialStore: GitCredentialStore,
    ) {
        @Volatile
        private var cachedCredentialIndex: Int = -1

        fun credentialProviders(): List<UsernamePasswordCredentialsProvider>? {
            val token = credentialStore.getToken()?.trim().orEmpty()
            if (token.isBlank()) return null

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
