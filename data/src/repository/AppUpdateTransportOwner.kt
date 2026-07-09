package com.lomo.data.repository

import com.lomo.domain.repository.AppUpdateTransportLifecycleRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.isActive

internal class AppUpdateTransportOwner private constructor(
    private val httpClientFactory: () -> HttpClient,
    initialHttpClient: HttpClient?,
) : AppUpdateTransportLifecycleRepository,
    AppUpdateHttpClientProvider,
    AutoCloseable {
    constructor(httpClient: HttpClient) : this(
        httpClientFactory = {
            error("App update HttpClient cannot be rebuilt without a factory")
        },
        initialHttpClient = httpClient,
    )

    constructor(httpClientFactory: () -> HttpClient) : this(
        httpClientFactory = httpClientFactory,
        initialHttpClient = null,
    )

    private val lock = Any()
    private var currentClient: ClientSlot? = initialHttpClient?.let(::ClientSlot)

    fun createDownloader(): AppUpdateApkDownloader = AppUpdateHttpDownloader(this)

    override suspend fun <T> withHttpClient(block: suspend (HttpClient) -> T): T {
        val slot = acquireClient()
        return try {
            block(slot.httpClient)
        } finally {
            releaseClient(slot)
        }
    }

    override fun closeUpdateTransport() {
        var clientToClose: HttpClient? = null
        synchronized(lock) {
            val slot = currentClient ?: return@synchronized
            slot.closeRequested = true
            if (slot.activeUses == 0) {
                currentClient = null
                clientToClose = slot.httpClient
            }
        }
        clientToClose?.close()
    }

    override fun close() = closeUpdateTransport()

    private fun acquireClient(): ClientSlot =
        synchronized(lock) {
            val slot =
                currentClient
                    ?.takeUnless { it.closeRequested }
                    ?.takeIf { it.httpClient.coroutineContext.isActive }
                    ?: ClientSlot(httpClientFactory()).also { currentClient = it }
            slot.activeUses += 1
            slot
        }

    private fun releaseClient(slot: ClientSlot) {
        var clientToClose: HttpClient? = null
        synchronized(lock) {
            slot.activeUses -= 1
            if (slot.activeUses == 0 && slot.closeRequested) {
                if (currentClient === slot) {
                    currentClient = null
                }
                clientToClose = slot.httpClient
            }
        }
        clientToClose?.close()
    }

    private class ClientSlot(
        val httpClient: HttpClient,
    ) {
        var activeUses: Int = 0
        var closeRequested: Boolean = false
    }

    internal companion object {
        fun createDefault(): AppUpdateTransportOwner =
            AppUpdateTransportOwner(
                httpClientFactory = ::createHttpClient,
            )

        private fun createHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                followRedirects = true
                install(HttpTimeout) {
                    connectTimeoutMillis = AppUpdateHttpDownloader.CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = AppUpdateHttpDownloader.READ_TIMEOUT_MS
                    socketTimeoutMillis = AppUpdateHttpDownloader.READ_TIMEOUT_MS
                }
            }
    }
}
