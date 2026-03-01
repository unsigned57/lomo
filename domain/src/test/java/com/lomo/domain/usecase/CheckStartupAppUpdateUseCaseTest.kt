package com.lomo.domain.usecase

import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.repository.PreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CheckStartupAppUpdateUseCaseTest {
    @Test
    fun `invoke keeps raw release notes without presentation normalization`() =
        runTest {
            val preferencesRepository = mockk<PreferencesRepository>()
            val appUpdateRepository = mockk<AppUpdateRepository>()
            val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

            every { preferencesRepository.isCheckUpdatesOnStartupEnabled() } returns flowOf(true)
            coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "0.1.0"

            val rawNotes = "[FORCE_UPDATE]\r\n" + "A".repeat(3500)
            coEvery { appUpdateRepository.fetchLatestRelease() } returns
                LatestAppRelease(
                    tagName = "v9.0.0",
                    htmlUrl = "https://example.com/release",
                    body = rawNotes,
                )

            val result =
                CheckStartupAppUpdateUseCase(
                    preferencesRepository = preferencesRepository,
                    appUpdateRepository = appUpdateRepository,
                    appRuntimeInfoRepository = appRuntimeInfoRepository,
                ).invoke()

            assertNotNull(result)
            assertEquals(rawNotes, result?.releaseNotes)
        }
}
