package com.lomo.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import com.lomo.domain.repository.AppRuntimeInfoRepository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber


class AppRuntimeInfoRepositoryImpl(
    private val context: Context,
) : AppRuntimeInfoRepository {
        override suspend fun getCurrentVersionName(): String =
            withContext(Dispatchers.Default) {
                try {
                    currentPackageInfo().versionName.orEmpty()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.w(error, "Failed to read current app version")
                    // behavior-contract: silent-result-ok: PackageManager version is optional; missing info is empty
                    ""
                }
            }

        override suspend fun getCurrentVersionCode(): Long? =
            withContext(Dispatchers.Default) {
                try {
                    PackageInfoCompat.getLongVersionCode(currentPackageInfo())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Timber.w(error, "Failed to read current app version code")
                    // behavior-contract: silent-result-ok: PackageManager version code is
                    // optional; missing info is null
                    null
                }
            }

        private fun currentPackageInfo(): PackageInfo =
            context.packageManager.getPackageInfo(context.packageName, 0)
    }
