package com.lomo.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import com.lomo.domain.repository.AppRuntimeInfoRepository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber


class AppRuntimeInfoRepositoryImpl(
    private val context: Context,
) : AppRuntimeInfoRepository {
        override suspend fun getCurrentVersionName(): String =
            withContext(Dispatchers.Default) {
                runCatching {
                    currentPackageInfo().versionName.orEmpty()
                }.onFailure { throwable ->
                    Timber.w(throwable, "Failed to read current app version")
                }.getOrDefault("")
            }

        override suspend fun getCurrentVersionCode(): Long? =
            withContext(Dispatchers.Default) {
                runCatching {
                    PackageInfoCompat.getLongVersionCode(currentPackageInfo())
                }.onFailure { throwable ->
                    Timber.w(throwable, "Failed to read current app version code")
                }.getOrNull()
            }

        private fun currentPackageInfo(): PackageInfo =
            context.packageManager.getPackageInfo(context.packageName, 0)
    }
