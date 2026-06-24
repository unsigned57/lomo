package com.lomo.data.repository

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.AppUpdateInstallAttempt
import com.lomo.domain.model.AppUpdateInstallPhase
import com.lomo.domain.model.AppUpdateInstallerOutcome
import com.lomo.domain.model.AppUpdateVerifiedPackageMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

internal interface AppUpdateInstallAttemptStore {
    fun observe(): Flow<AppUpdateInstallAttempt?>

    suspend fun save(attempt: AppUpdateInstallAttempt)

    fun update(transform: (AppUpdateInstallAttempt?) -> AppUpdateInstallAttempt?)
}

internal class JsonFileAppUpdateInstallAttemptStore(
    private val stateFile: File,
    private val json: Json = appUpdateInstallAttemptJson,
) : AppUpdateInstallAttemptStore {
    private val state = MutableStateFlow(readAttempt())

    override fun observe(): Flow<AppUpdateInstallAttempt?> = state

    override suspend fun save(attempt: AppUpdateInstallAttempt) {
        writeAttempt(attempt)
        state.value = attempt
    }

    override fun update(transform: (AppUpdateInstallAttempt?) -> AppUpdateInstallAttempt?) {
        state.update { current ->
            val next = transform(current)
            if (next == null) {
                stateFile.delete()
            } else {
                writeAttempt(next)
            }
            next
        }
    }

    private fun readAttempt(): AppUpdateInstallAttempt? {
        if (!stateFile.exists()) {
            return null
        }
        val text = stateFile.readText()
        if (text.isBlank()) {
            return null
        }
        return try {
            json.decodeFromString(StoredAppUpdateInstallAttempt.serializer(), text).toDomain()
        } catch (error: SerializationException) {
            throw IllegalStateException("Stored app update install attempt is unreadable", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Stored app update install attempt is invalid", error)
        }
    }

    private fun writeAttempt(attempt: AppUpdateInstallAttempt) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(
            json.encodeToString(
                StoredAppUpdateInstallAttempt.serializer(),
                attempt.toStored(),
            ),
        )
    }
}

internal fun AppUpdateInstallPhase.isTerminal(): Boolean =
    when (this) {
        AppUpdateInstallPhase.Completed,
        AppUpdateInstallPhase.Failed,
        AppUpdateInstallPhase.Cancelled,
        -> true

        AppUpdateInstallPhase.Recorded,
        AppUpdateInstallPhase.WaitingForInstallPermission,
        AppUpdateInstallPhase.Preparing,
        AppUpdateInstallPhase.Downloading,
        AppUpdateInstallPhase.Downloaded,
        AppUpdateInstallPhase.Installing,
        AppUpdateInstallPhase.WaitingForInstallerResult,
        -> false
    }

private fun AppUpdateInstallAttempt.toStored(): StoredAppUpdateInstallAttempt =
    StoredAppUpdateInstallAttempt(
        updateInfo = updateInfo.toStored(),
        phase = phase.name,
        progress = progress,
        permissionMessage = permissionMessage,
        downloadedFilePath = downloadedFilePath,
        verifiedPackageMetadata = verifiedPackageMetadata?.toStored(),
        installerOutcome = installerOutcome?.toStored(),
        failureMessage = failureMessage,
    )

private fun StoredAppUpdateInstallAttempt.toDomain(): AppUpdateInstallAttempt =
    AppUpdateInstallAttempt(
        updateInfo = updateInfo.toDomain(),
        phase = AppUpdateInstallPhase.valueOf(phase),
        progress = progress,
        permissionMessage = permissionMessage,
        downloadedFilePath = downloadedFilePath,
        verifiedPackageMetadata = verifiedPackageMetadata?.toDomain(),
        installerOutcome = installerOutcome?.toDomain(),
        failureMessage = failureMessage,
    )

private fun AppUpdateInfo.toStored(): StoredAppUpdateInfo =
    StoredAppUpdateInfo(
        url = url,
        version = version,
        releaseNotes = releaseNotes,
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
        expectedPackageName = expectedPackageName,
        expectedVersionName = expectedVersionName,
        expectedVersionCode = expectedVersionCode,
    )

private fun StoredAppUpdateInfo.toDomain(): AppUpdateInfo =
    AppUpdateInfo(
        url = url,
        version = version,
        releaseNotes = releaseNotes,
        apkDownloadUrl = apkDownloadUrl,
        apkFileName = apkFileName,
        apkSizeBytes = apkSizeBytes,
        expectedPackageName = expectedPackageName,
        expectedVersionName = expectedVersionName,
        expectedVersionCode = expectedVersionCode,
    )

private fun AppUpdateVerifiedPackageMetadata.toStored(): StoredAppUpdateVerifiedPackageMetadata =
    StoredAppUpdateVerifiedPackageMetadata(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        signerCertificateSha256Digests = signerCertificateSha256Digests,
    )

private fun StoredAppUpdateVerifiedPackageMetadata.toDomain(): AppUpdateVerifiedPackageMetadata =
    AppUpdateVerifiedPackageMetadata(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        signerCertificateSha256Digests = signerCertificateSha256Digests,
    )

private fun AppUpdateInstallerOutcome.toStored(): StoredAppUpdateInstallerOutcome =
    when (this) {
        AppUpdateInstallerOutcome.Installed ->
            StoredAppUpdateInstallerOutcome(
                type = StoredInstallerOutcomeType.INSTALLED,
                message = null,
            )

        is AppUpdateInstallerOutcome.Failed ->
            StoredAppUpdateInstallerOutcome(
                type = StoredInstallerOutcomeType.FAILED,
                message = message,
            )
    }

private fun StoredAppUpdateInstallerOutcome.toDomain(): AppUpdateInstallerOutcome =
    when (type) {
        StoredInstallerOutcomeType.INSTALLED -> AppUpdateInstallerOutcome.Installed
        StoredInstallerOutcomeType.FAILED ->
            AppUpdateInstallerOutcome.Failed(
                message = requireNotNull(message) { "Failed installer outcome requires a message" },
            )
    }

@Serializable
private data class StoredAppUpdateInstallAttempt(
    val updateInfo: StoredAppUpdateInfo,
    val phase: String,
    val progress: Int? = null,
    val permissionMessage: String? = null,
    val downloadedFilePath: String? = null,
    val verifiedPackageMetadata: StoredAppUpdateVerifiedPackageMetadata? = null,
    val installerOutcome: StoredAppUpdateInstallerOutcome? = null,
    val failureMessage: String? = null,
)

@Serializable
private data class StoredAppUpdateInfo(
    val url: String,
    val version: String,
    val releaseNotes: String,
    val apkDownloadUrl: String? = null,
    val apkFileName: String? = null,
    val apkSizeBytes: Long? = null,
    val expectedPackageName: String? = null,
    val expectedVersionName: String? = null,
    val expectedVersionCode: Long? = null,
)

@Serializable
private data class StoredAppUpdateVerifiedPackageMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long?,
    val signerCertificateSha256Digests: Set<String>,
)

@Serializable
private data class StoredAppUpdateInstallerOutcome(
    val type: StoredInstallerOutcomeType,
    val message: String? = null,
)

private enum class StoredInstallerOutcomeType {
    INSTALLED,
    FAILED,
}

private val appUpdateInstallAttemptJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
