package com.lomo.data.network
import com.lomo.data.repository.coercePositiveConcurrency
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
class SyncHttpClientProvider {
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
                .addInterceptor(SyncHttpRetryInterceptor())
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
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val IO_TIMEOUT_SECONDS = 60L
private const val MAX_IDLE_CONNECTIONS = 32
private const val KEEP_ALIVE_MINUTES = 5L
private const val DEFAULT_MAX_REQUESTS = 8
