package com.lomo.data.repository

import com.lomo.data.R
import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallAttempt
import com.lomo.domain.model.AppUpdateInstallPhase
import com.lomo.domain.model.AppUpdateInstallState
import com.lomo.domain.model.AppUpdateInstallerOutcome
import com.lomo.domain.repository.AppUpdateDownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    @Inject
    internal constructor(
        @ApplicationContext private val context: android.content.Context,
        private val downloader: AppUpdateApkDownloader,
        private val apkVerifier: AppUpdateApkVerifier,
        private val installerResultObserver: AppUpdateInstallerResultObserver,
        private val attemptStore: AppUpdateInstallAttemptStore =
            JsonFileAppUpdateInstallAttemptStore(File(context.filesDir, "update-install/attempt.json")),
        private val installerLauncher: AppUpdateInstallerLauncher = FileProviderAppUpdateInstallerLauncher(context),
    ) : AppUpdateDownloadRepository {
        @Volatile
        private var currentDownloadJob: Job? = null

        override fun observeInstallAttempt(): Flow<AppUpdateInstallAttempt?> = attemptStore.observe()

        override fun downloadAndInstall(updateInfo: AppUpdateInfo): Flow<AppUpdateInstallState> =
            flow {
                try {
                    currentDownloadJob = currentCoroutineContext()[Job]
                    attemptStore.save(
                        AppUpdateInstallAttempt(
                            updateInfo = updateInfo,
                            phase = AppUpdateInstallPhase.Recorded,
                        ),
                    )
                    val downloadUrl =
                        updateInfo.apkDownloadUrl?.takeIf { it.isNotBlank() }
                            ?: run {
                                val message = context.getString(R.string.app_update_missing_apk)
                                attemptStore.save(
                                    AppUpdateInstallAttempt(
                                        updateInfo = updateInfo,
                                        phase = AppUpdateInstallPhase.Failed,
                                        failureMessage = message,
                                    ),
                                )
                                emit(AppUpdateInstallState.Failed(message))
                                return@flow
                            }

                    if (!context.packageManager.canRequestPackageInstalls()) {
                        val message = context.getString(R.string.app_update_enable_installs)
                        attemptStore.save(
                            AppUpdateInstallAttempt(
                                updateInfo = updateInfo,
                                phase = AppUpdateInstallPhase.WaitingForInstallPermission,
                                permissionMessage = message,
                            ),
                        )
                        emit(AppUpdateInstallState.RequiresInstallPermission(message = message))
                        return@flow
                    }

                    attemptStore.save(
                        AppUpdateInstallAttempt(
                            updateInfo = updateInfo,
                            phase = AppUpdateInstallPhase.Preparing,
                        ),
                    )
                    emit(AppUpdateInstallState.Preparing)
                    val targetFile =
                        downloadApk(
                            downloadUrl = downloadUrl,
                            fileName = updateInfo.apkFileName ?: defaultApkName(updateInfo.version),
                        ) { progress ->
                            attemptStore.save(
                                AppUpdateInstallAttempt(
                                    updateInfo = updateInfo,
                                    phase = AppUpdateInstallPhase.Downloading,
                                    progress = progress,
                                ),
                            )
                            emit(AppUpdateInstallState.Downloading(progress))
                        }
                    val downloadedPath = targetFile.absolutePath
                    attemptStore.save(
                        AppUpdateInstallAttempt(
                            updateInfo = updateInfo,
                            phase = AppUpdateInstallPhase.Downloaded,
                            progress = 100,
                            downloadedFilePath = downloadedPath,
                        ),
                    )

                    emitPersistedInstallStatesAfterDownload(
                        updateInfo = updateInfo,
                        targetFile = targetFile,
                        downloadedPath = downloadedPath,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordCurrentAttemptFailure(error)
                    throw error
                } finally {
                    currentDownloadJob = null
                }
            }.flowOn(Dispatchers.IO)

        override fun cancelCurrentDownload() {
            currentDownloadJob?.cancel()
            attemptStore.update { existing ->
                existing?.copy(phase = AppUpdateInstallPhase.Cancelled)
            }
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
            installerLauncher.launch(apkFile)
        }

        private fun recordCurrentAttemptFailure(error: Throwable) {
            attemptStore.update { existing ->
                when {
                    existing == null -> null
                    existing.phase.isTerminal() -> existing
                    existing.phase == AppUpdateInstallPhase.WaitingForInstallPermission -> existing
                    else ->
                        existing.copy(
                            phase = AppUpdateInstallPhase.Failed,
                            installerOutcome = null,
                            failureMessage = error.toInstallAttemptFailureMessage(),
                        )
                }
            }
        }

        private suspend fun FlowCollector<AppUpdateInstallState>.emitPersistedInstallStatesAfterDownload(
            updateInfo: AppUpdateInfo,
            targetFile: File,
            downloadedPath: String,
        ) {
            when (val verification = apkVerifier.verify(targetFile, updateInfo)) {
                is DownloadedApkVerificationResult.Invalid -> {
                    attemptStore.save(
                        AppUpdateInstallAttempt(
                            updateInfo = updateInfo,
                            phase = AppUpdateInstallPhase.Failed,
                            downloadedFilePath = downloadedPath,
                            failureMessage = verification.message,
                        ),
                    )
                    emit(AppUpdateInstallState.Failed(verification.message))
                    return
                }

                is DownloadedApkVerificationResult.Valid -> {
                    val verifiedMetadata = verification.metadata.toDomainMetadata()
                    attemptStore.save(
                        AppUpdateInstallAttempt(
                            updateInfo = updateInfo,
                            phase = AppUpdateInstallPhase.Installing,
                            downloadedFilePath = downloadedPath,
                            verifiedPackageMetadata = verifiedMetadata,
                        ),
                    )
                    emit(AppUpdateInstallState.Installing)
                    launchInstaller(targetFile)
                    attemptStore.save(
                        AppUpdateInstallAttempt(
                            updateInfo = updateInfo,
                            phase = AppUpdateInstallPhase.WaitingForInstallerResult,
                            downloadedFilePath = downloadedPath,
                            verifiedPackageMetadata = verifiedMetadata,
                        ),
                    )
                    emit(AppUpdateInstallState.WaitingForInstallerResult)
                    when (
                        val installerResult =
                            installerResultObserver.awaitInstallerResult(
                                verifiedDownloadedApk = verification.metadata,
                                updateInfo = updateInfo,
                            )
                    ) {
                        AppUpdateInstallerResult.Installed -> {
                            attemptStore.save(
                                AppUpdateInstallAttempt(
                                    updateInfo = updateInfo,
                                    phase = AppUpdateInstallPhase.Completed,
                                    downloadedFilePath = downloadedPath,
                                    verifiedPackageMetadata = verifiedMetadata,
                                    installerOutcome = AppUpdateInstallerOutcome.Installed,
                                ),
                            )
                            emit(AppUpdateInstallState.Completed)
                        }

                        is AppUpdateInstallerResult.Failed -> {
                            attemptStore.save(
                                AppUpdateInstallAttempt(
                                    updateInfo = updateInfo,
                                    phase = AppUpdateInstallPhase.Failed,
                                    downloadedFilePath = downloadedPath,
                                    verifiedPackageMetadata = verifiedMetadata,
                                    installerOutcome = AppUpdateInstallerOutcome.Failed(installerResult.message),
                                    failureMessage = installerResult.message,
                                ),
                            )
                            emit(AppUpdateInstallState.Failed(installerResult.message))
                        }
                    }
                }
            }
        }

        private companion object {
            const val UPDATE_DOWNLOAD_DIRECTORY = "updates"
        }
    }

private fun Throwable.toInstallAttemptFailureMessage(): String {
    val messageText = message?.takeIf { it.isNotBlank() }
    return if (messageText == null) {
        javaClass.name
    } else {
        messageText
    }
}

private fun sanitizeFileName(rawName: String): String =
    rawName
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .ifBlank { "lomo-update.apk" }

private fun defaultApkName(version: String): String =
    "lomo-v${version.ifBlank { "latest" }}.apk"
