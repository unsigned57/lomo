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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateDownloadRepositoryImpl
    internal constructor(
        @ApplicationContext private val context: android.content.Context,
        private val downloader: AppUpdateHttpDownloader,
    ) : AppUpdateDownloadRepository {
        @Inject
        constructor(
            @ApplicationContext context: android.content.Context,
        ) : this(
            context = context,
            downloader = AppUpdateHttpDownloader(),
        )

        @Volatile
        private var currentDownloadJob: Job? = null

        override fun downloadAndInstall(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState> =
            flow {
                try {
                    currentDownloadJob = currentCoroutineContext()[Job]
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
                } finally {
                    currentDownloadJob = null
                }
            }.flowOn(Dispatchers.IO)

        override fun cancelCurrentDownload() {
            currentDownloadJob?.cancel()
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

            try {
                downloader.download(
                    outputFile = outputFile,
                    downloadUrl = downloadUrl,
                    onProgress = onProgress,
                )

                if (outputFile.length() <= 0L) {
                    throw IOException(context.getString(R.string.app_update_empty_file))
                }
                return outputFile
            } catch (error: AppUpdateDownloadHttpException) {
                throw IOException(context.getString(R.string.app_update_http_failed, error.statusCode), error)
            }
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
            const val APK_MIME_TYPE = "application/vnd.android.package-archive"
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
