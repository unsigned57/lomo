package com.lomo.data.security

import com.lomo.domain.model.AppLockPreference
import kotlinx.coroutines.flow.Flow

interface AppLockPreferenceSource {
    suspend fun read(): AppLockPreference

    fun observe(): Flow<AppLockPreference>
}
