package com.lomo.data.repository

import com.lomo.data.s3.S3_MULTIPART_PART_CONCURRENCY
import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

data class SyncPerformanceProfile(
    val s3ActionConcurrency: Int = S3_ACTION_CONCURRENCY,
    val s3LargeTransferSmallLanePermits: Int = S3_LARGE_TRANSFER_SMALL_LANE_PERMITS,
    val s3LargeTransferConcurrency: Int = S3_LARGE_TRANSFER_CONCURRENCY,
    val s3VerificationConcurrency: Int = S3_VERIFICATION_CONCURRENCY,
    val s3MultipartPartConcurrency: Int = S3_MULTIPART_PART_CONCURRENCY,
    val s3MaxConnections: Int = S3_DEFAULT_MAX_CONNECTIONS,
    val s3MaxConnectionsPerHost: Int = S3_DEFAULT_MAX_CONNECTIONS_PER_HOST,
    val webDavActionConcurrency: Int = WEBDAV_DEFAULT_ACTION_CONCURRENCY,
    val webDavFingerprintConcurrency: Int = WEBDAV_FINGERPRINT_CONCURRENCY,
    val webDavInitialOverlapConcurrency: Int = WEBDAV_DEFAULT_INITIAL_OVERLAP_CONCURRENCY,
    val webDavListConcurrency: Int = WEBDAV_DEFAULT_LIST_CONCURRENCY,
)

interface SyncPerformanceTuner {
    fun currentProfile(): SyncPerformanceProfile
}

object DisabledSyncPerformanceTuner : SyncPerformanceTuner {
    override fun currentProfile(): SyncPerformanceProfile = SyncPerformanceProfile()
}

internal fun SyncPerformanceProfile.webDavMaxRequests(): Int =
    maxOf(
        webDavActionConcurrency,
        webDavFingerprintConcurrency,
        webDavInitialOverlapConcurrency,
        webDavListConcurrency,
    ).coercePositiveConcurrency()

internal fun Int.coercePositiveConcurrency(): Int = coerceAtLeast(1)

@Singleton
class AndroidSyncPerformanceTuner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SyncPerformanceTuner {
        override fun currentProfile(): SyncPerformanceProfile {
            val memoryClass = context.activityManager()?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
            val transport = context.activeTransportClass()
            val base =
                when (transport) {
                    TransportClass.WIFI, TransportClass.ETHERNET ->
                        SyncPerformanceProfile(
                            s3ActionConcurrency = WIFI_S3_ACTION_CONCURRENCY,
                            s3LargeTransferSmallLanePermits = WIFI_S3_LARGE_TRANSFER_PERMITS,
                            s3LargeTransferConcurrency = WIFI_S3_LARGE_TRANSFER_CONCURRENCY,
                            s3MultipartPartConcurrency = WIFI_S3_MULTIPART_PART_CONCURRENCY,
                            s3MaxConnections = WIFI_S3_MAX_CONNECTIONS,
                            s3MaxConnectionsPerHost = WIFI_S3_MAX_CONNECTIONS_PER_HOST,
                            webDavActionConcurrency = WIFI_WEBDAV_CONCURRENCY,
                            webDavFingerprintConcurrency = WIFI_WEBDAV_CONCURRENCY,
                            webDavInitialOverlapConcurrency = WIFI_WEBDAV_CONCURRENCY,
                            webDavListConcurrency = WIFI_WEBDAV_CONCURRENCY,
                        )

                    TransportClass.CELLULAR, TransportClass.OTHER ->
                        SyncPerformanceProfile(
                            s3ActionConcurrency = CELLULAR_S3_ACTION_CONCURRENCY,
                            s3LargeTransferSmallLanePermits = CELLULAR_S3_LARGE_TRANSFER_PERMITS,
                            s3LargeTransferConcurrency = CELLULAR_S3_LARGE_TRANSFER_CONCURRENCY,
                            s3MultipartPartConcurrency = CELLULAR_S3_MULTIPART_PART_CONCURRENCY,
                            s3MaxConnections = CELLULAR_S3_MAX_CONNECTIONS,
                            s3MaxConnectionsPerHost = CELLULAR_S3_MAX_CONNECTIONS_PER_HOST,
                            webDavActionConcurrency = CELLULAR_WEBDAV_CONCURRENCY,
                            webDavFingerprintConcurrency = CELLULAR_WEBDAV_CONCURRENCY,
                            webDavInitialOverlapConcurrency = CELLULAR_WEBDAV_CONCURRENCY,
                            webDavListConcurrency = CELLULAR_WEBDAV_CONCURRENCY,
                        )

                    TransportClass.UNKNOWN -> SyncPerformanceProfile()
                }
            return when {
                memoryClass < LOW_MEMORY_CLASS_MB ->
                    base.copy(
                        s3ActionConcurrency = minOf(base.s3ActionConcurrency, LOW_MEMORY_S3_ACTION_CONCURRENCY),
                        s3LargeTransferSmallLanePermits =
                            minOf(base.s3LargeTransferSmallLanePermits, LOW_MEMORY_S3_LARGE_TRANSFER_PERMITS),
                        s3LargeTransferConcurrency = LOW_MEMORY_S3_LARGE_TRANSFER_CONCURRENCY,
                        s3MultipartPartConcurrency =
                            minOf(base.s3MultipartPartConcurrency, LOW_MEMORY_S3_MULTIPART_PART_CONCURRENCY),
                        s3MaxConnections = minOf(base.s3MaxConnections, LOW_MEMORY_S3_MAX_CONNECTIONS),
                        s3MaxConnectionsPerHost =
                            minOf(base.s3MaxConnectionsPerHost, LOW_MEMORY_S3_MAX_CONNECTIONS_PER_HOST),
                        webDavActionConcurrency =
                            minOf(base.webDavActionConcurrency, LOW_MEMORY_WEBDAV_CONCURRENCY),
                        webDavFingerprintConcurrency =
                            minOf(base.webDavFingerprintConcurrency, LOW_MEMORY_WEBDAV_CONCURRENCY),
                        webDavInitialOverlapConcurrency =
                            minOf(base.webDavInitialOverlapConcurrency, LOW_MEMORY_WEBDAV_CONCURRENCY),
                        webDavListConcurrency =
                            minOf(base.webDavListConcurrency, LOW_MEMORY_WEBDAV_CONCURRENCY),
                    )

                else -> base
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal interface SyncPerformanceTuningModule {
    @Binds
    fun bindSyncPerformanceTuner(impl: AndroidSyncPerformanceTuner): SyncPerformanceTuner
}

private enum class TransportClass {
    WIFI,
    ETHERNET,
    CELLULAR,
    OTHER,
    UNKNOWN,
}

private fun Context.activityManager(): ActivityManager? =
    getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

private fun Context.activeTransportClass(): TransportClass {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return TransportClass.UNKNOWN
    val capabilities =
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return TransportClass.UNKNOWN
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportClass.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportClass.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportClass.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> TransportClass.OTHER
        else -> TransportClass.UNKNOWN
    }
}

private const val DEFAULT_MEMORY_CLASS_MB = 192
private const val LOW_MEMORY_CLASS_MB = 192
private const val WIFI_S3_ACTION_CONCURRENCY = 12
private const val WIFI_S3_LARGE_TRANSFER_PERMITS = 3
private const val WIFI_S3_LARGE_TRANSFER_CONCURRENCY = 3
private const val WIFI_S3_MULTIPART_PART_CONCURRENCY = 6
private const val WIFI_S3_MAX_CONNECTIONS = 16
private const val WIFI_S3_MAX_CONNECTIONS_PER_HOST = 12
private const val WIFI_WEBDAV_CONCURRENCY = 10
private const val CELLULAR_S3_ACTION_CONCURRENCY = 6
private const val CELLULAR_S3_LARGE_TRANSFER_PERMITS = 2
private const val CELLULAR_S3_LARGE_TRANSFER_CONCURRENCY = 2
private const val CELLULAR_S3_MULTIPART_PART_CONCURRENCY = 2
private const val CELLULAR_S3_MAX_CONNECTIONS = 8
private const val CELLULAR_S3_MAX_CONNECTIONS_PER_HOST = 6
private const val CELLULAR_WEBDAV_CONCURRENCY = 6
private const val LOW_MEMORY_S3_ACTION_CONCURRENCY = 5
private const val LOW_MEMORY_S3_LARGE_TRANSFER_PERMITS = 2
private const val LOW_MEMORY_S3_LARGE_TRANSFER_CONCURRENCY = 1
private const val LOW_MEMORY_S3_MULTIPART_PART_CONCURRENCY = 2
private const val LOW_MEMORY_S3_MAX_CONNECTIONS = 6
private const val LOW_MEMORY_S3_MAX_CONNECTIONS_PER_HOST = 4
private const val LOW_MEMORY_WEBDAV_CONCURRENCY = 5
internal const val S3_DEFAULT_MAX_CONNECTIONS = 12
internal const val S3_DEFAULT_MAX_CONNECTIONS_PER_HOST = 8
internal const val S3_LARGE_TRANSFER_CONCURRENCY = 2
internal const val WEBDAV_DEFAULT_ACTION_CONCURRENCY = 8
internal const val WEBDAV_DEFAULT_INITIAL_OVERLAP_CONCURRENCY = 8
internal const val WEBDAV_DEFAULT_LIST_CONCURRENCY = 8
