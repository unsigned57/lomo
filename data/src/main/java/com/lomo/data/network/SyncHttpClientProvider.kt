package com.lomo.data.network

import com.lomo.data.repository.coercePositiveConcurrency
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncHttpClientProvider
    @Inject
    constructor() {
        private val baseClient: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                .addInterceptor(ExponentialBackoffRetryInterceptor())
                .build()

        fun webDavClient(
            username: String,
            password: String,
            maxRequests: Int = DEFAULT_MAX_REQUESTS,
            maxRequestsPerHost: Int = maxRequests,
        ): OkHttpClient =
            baseClient
                .newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        this.maxRequests = maxRequests.coercePositiveConcurrency()
                        this.maxRequestsPerHost = maxRequestsPerHost.coercePositiveConcurrency()
                    },
                )
                .addInterceptor(
                    Interceptor { chain ->
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .header("Authorization", Credentials.basic(username, password))
                                .build(),
                        )
                    },
                ).build()

        fun s3Client(
            maxRequests: Int,
            maxRequestsPerHost: Int,
        ): OkHttpClient =
            baseClient
                .newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        this.maxRequests = maxRequests.coercePositiveConcurrency()
                        this.maxRequestsPerHost = maxRequestsPerHost.coercePositiveConcurrency()
                    },
                ).build()
    }

private class ExponentialBackoffRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in RETRYABLE_METHODS) {
            return chain.proceed(request)
        }
        return retryRequest(chain, request)
    }

    private fun retryRequest(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
    ): Response {
        var attempt = 0
        var lastFailure: IOException? = null
        while (attempt < MAX_ATTEMPTS) {
            try {
                val response = chain.proceed(request)
                if (!response.shouldRetry()) {
                    return response
                }
                response.close()
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt + 1 >= MAX_ATTEMPTS) {
                    return rethrow(failure)
                }
            }
            attempt += 1
            try {
                Thread.sleep(INITIAL_BACKOFF_MS shl (attempt - 1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return interruptedRetry(interrupted)
            }
        }
        return retryExhausted(lastFailure)
    }
}

private fun Response.shouldRetry(): Boolean = code == HTTP_TOO_MANY_REQUESTS || code in HTTP_SERVER_ERROR_RANGE

private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val IO_TIMEOUT_SECONDS = 60L
private const val MAX_IDLE_CONNECTIONS = 8
private const val KEEP_ALIVE_MINUTES = 5L
private const val DEFAULT_MAX_REQUESTS = 8
private const val MAX_ATTEMPTS = 3
private const val INITIAL_BACKOFF_MS = 200L
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private val RETRYABLE_METHODS = setOf("GET", "HEAD", "PROPFIND", "OPTIONS", "REPORT")

private fun rethrow(failure: IOException): Response = throw failure

private fun interruptedRetry(interrupted: InterruptedException): Response =
    throw IOException("Retry interrupted", interrupted)

private fun retryExhausted(lastFailure: IOException?): Response =
    throw IOException("HTTP retry exhausted", lastFailure)
