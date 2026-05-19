package com.lomo.domain.testing.fakes

import com.lomo.domain.model.AppUpdateInfo
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.model.SyncConflictFile
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.AppVersionRepository
import com.lomo.domain.repository.ShareImageRepository
import com.lomo.domain.repository.SyncConflictBackupRepository

class FakeShareImageRepository : ShareImageRepository {
    data class StoredImage(
        val pngBytes: ByteArray,
        val fileNamePrefix: String,
    )

    val storedImages = mutableListOf<StoredImage>()
    var nextPath: String = "/tmp/share.png"

    override suspend fun storeShareImage(
        pngBytes: ByteArray,
        fileNamePrefix: String,
    ): String {
        storedImages += StoredImage(pngBytes = pngBytes, fileNamePrefix = fileNamePrefix)
        return nextPath
    }
}

class FakeSyncConflictBackupRepository : SyncConflictBackupRepository {
    data class BackupRequest(
        val files: List<SyncConflictFile>,
        val readResults: Map<String, ByteArray?>,
    )

    val backupRequests = mutableListOf<BackupRequest>()

    override suspend fun backupFiles(
        files: List<SyncConflictFile>,
        localFileReader: suspend (String) -> ByteArray?,
    ) {
        backupRequests +=
            BackupRequest(
                files = files,
                readResults = files.associate { file -> file.relativePath to localFileReader(file.relativePath) },
            )
    }
}

class FakeAppUpdateRepository(
    var latestRelease: LatestAppRelease? = null,
) : AppUpdateRepository {
    var fetchLatestReleaseCallCount = 0
        private set

    override suspend fun fetchLatestRelease(): LatestAppRelease? {
        fetchLatestReleaseCallCount += 1
        return latestRelease
    }
}

class FakeAppRuntimeInfoRepository(
    var currentVersionName: String = "1.0.0",
) : AppRuntimeInfoRepository {
    var getCurrentVersionNameCallCount = 0
        private set

    override suspend fun getCurrentVersionName(): String {
        getCurrentVersionNameCallCount += 1
        return currentVersionName
    }
}

class FakeAppVersionRepository(
    var lastAppVersion: String? = null,
) : AppVersionRepository {
    val updatedVersions = mutableListOf<String?>()

    override suspend fun getLastAppVersionOnce(): String? = lastAppVersion

    override suspend fun updateLastAppVersion(version: String?) {
        updatedVersions += version
        lastAppVersion = version
    }
}

fun AppUpdateInfo?.versionOrNull(): String? = this?.version
