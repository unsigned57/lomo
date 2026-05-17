package com.lomo.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

internal class AppUpdateHttpDownloader(
    private val httpClient: HttpClient = createAppUpdateHttpClient(),
) {
    suspend fun download(
        downloadUrl: String,
        outputFile: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        httpClient
            .prepareGet(downloadUrl) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw AppUpdateDownloadHttpException(response.status.value)
                }

                val totalBytes =
                    response.headers[HttpHeaders.ContentLength]
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                val channel = response.bodyAsChannel()
                var downloadedBytes = 0L
                var lastProgress = INITIAL_PROGRESS_SENTINEL
                onProgress(PROGRESS_MIN)

                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = 0
                    do {
                        currentCoroutineContext().ensureActive()
                        read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            lastProgress =
                                emitDownloadProgress(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    lastProgress = lastProgress,
                                    onProgress = onProgress,
                                )
                        }
                    } while (read >= 0)
                    output.fd.sync()
                }
            }
    }

    private suspend fun emitDownloadProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
        lastProgress: Int,
        onProgress: suspend (Int) -> Unit,
    ): Int {
        if (totalBytes == null) {
            return lastProgress
        }
        val progress =
            ((downloadedBytes * PROGRESS_MAX) / totalBytes)
                .toInt()
                .coerceIn(PROGRESS_MIN, PROGRESS_MAX)
        if (progress == lastProgress) {
            return lastProgress
        }
        onProgress(progress)
        return progress
    }

    internal companion object {
        const val USER_AGENT = "Lomo-App"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val PROGRESS_MIN = 0
        const val PROGRESS_MAX = 100
        const val INITIAL_PROGRESS_SENTINEL = -1
    }
}

internal class AppUpdateDownloadHttpException(
    val statusCode: Int,
) : IllegalStateException("Unexpected app update HTTP status $statusCode")

private fun createAppUpdateHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = AppUpdateHttpDownloader.CONNECT_TIMEOUT_MS
            requestTimeoutMillis = AppUpdateHttpDownloader.READ_TIMEOUT_MS
            socketTimeoutMillis = AppUpdateHttpDownloader.READ_TIMEOUT_MS
        }
    }
