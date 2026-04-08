package com.lomo.data.repository

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.content.FileProvider
import com.lomo.data.R
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.repository.AppUpdateDownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateDownloadRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: android.content.Context,
    ) : AppUpdateDownloadRepository {
        @Volatile
        private var currentConnection: HttpURLConnection? = null

        override fun downloadAndInstall(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState> =
            flow {
                val downloadUrl =
                    updateInfo.apkDownloadUrl?.takeIf { it.isNotBlank() }
                        ?: run {
                            emit(AppUpdateInstallState.Failed(context.getString(R.string.app_update_missing_apk)))
                            return@flow
                        }

                gateInstallPermissionBeforeDownload(
                    canRequestPackageInstalls = context.packageManager.canRequestPackageInstalls(),
                    missingPermissionMessage = context.getString(R.string.app_update_enable_installs),
                ) {
                    emit(AppUpdateInstallState.Preparing)
                    val targetFile =
                        downloadApk(
                            downloadUrl = downloadUrl,
                            fileName = updateInfo.apkFileName ?: defaultApkName(updateInfo.version),
                        ) { progress ->
                            emit(AppUpdateInstallState.Downloading(progress))
                        }

                    emitInstallStatesAfterDownload {
                        launchInstaller(targetFile)
                    }
                }
            }.flowOn(Dispatchers.IO)

        override fun cancelCurrentDownload() {
            currentConnection?.disconnect()
        }

        private suspend fun downloadApk(
            downloadUrl: String,
            fileName: String,
            onProgress: suspend (Int) -> Unit,
        ): File {
            currentCoroutineContext().ensureActive()
            val outputDir = File(context.cacheDir, UPDATE_DOWNLOAD_DIRECTORY).apply { mkdirs() }
            val outputFile = File(outputDir, sanitizeFileName(fileName))
            outputFile.delete()

            val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            currentConnection = connection

            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException(context.getString(R.string.app_update_http_failed, responseCode))
                }

                val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
                copyResponseToFile(
                    input = connection.inputStream,
                    outputFile = outputFile,
                    totalBytes = totalBytes,
                    onProgress = onProgress,
                )

                if (outputFile.length() <= 0L) {
                    throw IOException(context.getString(R.string.app_update_empty_file))
                }
                return outputFile
            } finally {
                currentConnection?.disconnect()
                currentConnection = null
            }
        }

        private suspend fun copyResponseToFile(
            input: InputStream,
            outputFile: File,
            totalBytes: Long?,
            onProgress: suspend (Int) -> Unit,
        ) {
            var downloadedBytes = 0L
            var lastProgress = INITIAL_PROGRESS_SENTINEL
            onProgress(PROGRESS_MIN)

            input.use { inputStream ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = inputStream.read(buffer)
                        if (read < 0) {
                            break
                        }
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

        private fun launchInstaller(apkFile: File) {
            val apkUri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile,
                )
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            try {
                context.startActivity(intent)
            } catch (error: ActivityNotFoundException) {
                throw IllegalStateException(context.getString(R.string.app_update_no_installer), error)
            }
        }

        private fun sanitizeFileName(rawName: String): String =
            rawName
                .replace(Regex("[^A-Za-z0-9._-]"), "-")
                .ifBlank { "lomo-update.apk" }

        private fun defaultApkName(version: String): String =
            "lomo-v${version.ifBlank { "latest" }}.apk"

        private companion object {
            const val UPDATE_DOWNLOAD_DIRECTORY = "updates"
            const val USER_AGENT = "Lomo-App"
            const val CONNECT_TIMEOUT_MS = 15_000
            const val READ_TIMEOUT_MS = 60_000
            const val APK_MIME_TYPE = "application/vnd.android.package-archive"
            const val PROGRESS_MIN = 0
            const val PROGRESS_MAX = 100
            const val INITIAL_PROGRESS_SENTINEL = -1
        }
    }

internal suspend fun FlowCollector<AppUpdateInstallState>.gateInstallPermissionBeforeDownload(
    canRequestPackageInstalls: Boolean,
    missingPermissionMessage: String,
    onPermissionGranted: suspend FlowCollector<AppUpdateInstallState>.() -> Unit,
) {
    if (!canRequestPackageInstalls) {
        emit(AppUpdateInstallState.RequiresInstallPermission(message = missingPermissionMessage))
        return
    }

    onPermissionGranted()
}

internal suspend fun FlowCollector<AppUpdateInstallState>.emitInstallStatesAfterDownload(
    launchInstaller: () -> Unit,
) {
    emit(AppUpdateInstallState.Installing)
    launchInstaller()
    emit(AppUpdateInstallState.Completed)
}
