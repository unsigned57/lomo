package com.lomo.domain.repository

import com.lomo.domain.model.ShareCardStyle
import com.lomo.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesRepositoryDefaultsTest {
    @Test
    fun `setShowInputHintsEnabled delegates to legacy setter by default`() =
        runTest {
            val repository = LegacySetterOnlyPreferencesRepository()

            repository.setShowInputHintsEnabled(enabled = false)

            assertEquals(false, repository.lastShowInputHintsSetValue)
        }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class LegacySetterOnlyPreferencesRepository : PreferencesRepository {
        var lastShowInputHintsSetValue: Boolean? = null

        override fun getDateFormat(): Flow<String> = flowOf("yyyy-MM-dd")

        override suspend fun setDateFormat(format: String) = Unit

        override fun getTimeFormat(): Flow<String> = flowOf("HH:mm:ss")

        override suspend fun setTimeFormat(format: String) = Unit

        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override fun getStorageFilenameFormat(): Flow<String> = flowOf("yyyy_MM_dd")

        override suspend fun setStorageFilenameFormat(format: String) = Unit

        override fun getStorageTimestampFormat(): Flow<String> = flowOf("HH:mm:ss")

        override suspend fun setStorageTimestampFormat(format: String) = Unit

        override fun isHapticFeedbackEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun setHapticFeedbackEnabled(enabled: Boolean) = Unit

        override fun isShowInputHintsEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun setShowInputHints(enabled: Boolean) {
            lastShowInputHintsSetValue = enabled
        }

        override fun isDoubleTapEditEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun setDoubleTapEditEnabled(enabled: Boolean) = Unit

        override fun isAppLockEnabled(): Flow<Boolean> = flowOf(false)

        override suspend fun setAppLockEnabled(enabled: Boolean) = Unit

        override fun isCheckUpdatesOnStartupEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun setCheckUpdatesOnStartup(enabled: Boolean) = Unit

        override fun getShareCardStyle(): Flow<ShareCardStyle> = flowOf(ShareCardStyle.CLEAN)

        override suspend fun setShareCardStyle(style: ShareCardStyle) = Unit

        override fun isShareCardShowTimeEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun setShareCardShowTime(enabled: Boolean) = Unit

        override fun isShareCardShowBrandEnabled(): Flow<Boolean> = flowOf(true)

        override suspend fun setShareCardShowBrand(enabled: Boolean) = Unit
    }
}
