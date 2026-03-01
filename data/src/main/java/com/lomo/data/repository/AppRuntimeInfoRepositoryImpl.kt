package com.lomo.data.repository

import android.content.Context
import com.lomo.domain.repository.AppRuntimeInfoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class AppRuntimeInfoRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : AppRuntimeInfoRepository {
        override suspend fun getCurrentVersionName(): String =
            withContext(Dispatchers.Default) {
                runCatching {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    packageInfo.versionName.orEmpty()
                }.onFailure { throwable ->
                    Timber.w(throwable, "Failed to read current app version")
                }.getOrDefault("")
            }
    }
