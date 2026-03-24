package com.lomo.data.repository

import com.lomo.domain.model.WebDavSyncResult
import com.lomo.domain.model.WebDavSyncState
import com.lomo.data.webdav.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal data class WebDavResolvedConfig(
    val endpointUrl: String,
    val username: String,
    val password: String,
)

@Singleton
class WebDavSyncRepositorySupport
    @Inject
    constructor(
        private val runtime: WebDavSyncRepositoryContext,
    ) {
        suspend fun <T> runWebDavIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

        internal suspend fun resolveConfig(): WebDavResolvedConfig? {
            val provider = webDavProviderFromPreference(runtime.dataStore.webDavProvider.first())
            val baseUrl = runtime.dataStore.webDavBaseUrl.first()
            val endpointUrl = runtime.dataStore.webDavEndpointUrl.first()
            val username =
                (runtime.dataStore.webDavUsername.first() ?: runtime.credentialStore.getUsername())
                    ?.trim()
                    .orEmpty()
            val password = runtime.credentialStore.getPassword()?.trim().orEmpty()
            val resolved =
                if (
                    runtime.dataStore.webDavSyncEnabled.first() &&
                    username.isNotBlank() &&
                    password.isNotBlank()
                ) {
                    runtime.endpointResolver
                        .resolve(provider, baseUrl, endpointUrl, username)
                        ?.takeIf(String::isNotBlank)
                        ?.let { resolvedUrl ->
                            WebDavResolvedConfig(
                                endpointUrl = resolvedUrl,
                                username = username,
                                password = password,
                            )
                        }
                } else {
                    null
                }
            if (resolved == null) {
                runtime.stateHolder.state.value = WebDavSyncState.NotConfigured
            }
            return resolved
        }

        internal fun createClient(config: WebDavResolvedConfig): WebDavClient =
            runtime.clientFactory.create(config.endpointUrl, config.username, config.password)

        fun notConfiguredResult(): WebDavSyncResult {
            runtime.stateHolder.state.value = WebDavSyncState.NotConfigured
            return WebDavSyncResult.NotConfigured
        }

        fun mapError(error: Throwable): WebDavSyncResult.Error {
            if (error is CancellationException) {
                throw error
            }

            val message = error.message?.takeIf(String::isNotBlank) ?: "WebDAV sync failed"
            Timber.e(error, "WebDAV sync failed")
            runtime.stateHolder.state.value = WebDavSyncState.Error(message, System.currentTimeMillis())
            return WebDavSyncResult.Error(message, error)
        }

        fun mapConnectionTestError(error: Throwable): WebDavSyncResult.Error {
            if (error is CancellationException) {
                throw error
            }

            val message = error.message?.takeIf(String::isNotBlank) ?: "WebDAV connection test failed"
            Timber.e(error, "WebDAV connection test failed")
            return WebDavSyncResult.Error(message, error)
        }
    }
