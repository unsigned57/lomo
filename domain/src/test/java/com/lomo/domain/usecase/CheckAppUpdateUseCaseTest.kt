package com.lomo.domain.usecase

import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.repository.AppRuntimeInfoRepository
import com.lomo.domain.repository.AppUpdateRepository
import com.lomo.domain.testing.DomainFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

/*
 * Test Contract:
 * - Unit under test: CheckAppUpdateUseCase
 * - Behavior focus: unconditional release lookup, semantic version comparison, and force-update override for manual checks.
 * - Observable outcomes: returned AppUpdateInfo or null, selected remote version, and preserved release metadata when a newer release exists.
 * - Red phase: Fails before the fix because manual update checking does not exist as a domain capability yet.
 * - Excludes: preferences gating, repository transport implementation, and UI presentation normalization.
 */
class CheckAppUpdateUseCaseTest : DomainFunSpec() {
    init {
        test("invoke returns update info when remote semantic version is newer") {
            runTest {
                        val appUpdateRepository = mockk<AppUpdateRepository>()
                        val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

                        coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "1.0.0-debug"
                        coEvery { appUpdateRepository.fetchLatestRelease() } returns
                            LatestAppRelease(
                                tagName = "v1.1.0",
                                htmlUrl = "https://example.com/releases/1.1.0",
                                body = "new release",
                            )

                        val result =
                            CheckAppUpdateUseCase(
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldNotBe null
                        result?.version shouldBe "1.1.0"
                        result?.url shouldBe "https://example.com/releases/1.1.0"
                        result?.releaseNotes shouldBe "new release"
                    }
        }
    }
    init {
        test("invoke returns null when remote version is not newer and force marker is absent") {
            runTest {
                        val appUpdateRepository = mockk<AppUpdateRepository>()
                        val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

                        coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "1.2.3"
                        coEvery { appUpdateRepository.fetchLatestRelease() } returns
                            LatestAppRelease(
                                tagName = "v1.2.3",
                                htmlUrl = "https://example.com/releases/1.2.3",
                                body = "same version",
                            )

                        val result =
                            CheckAppUpdateUseCase(
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldBe null
                    }
        }
    }
    init {
        test("invoke returns update info when force marker is present even if versions match") {
            runTest {
                        val appUpdateRepository = mockk<AppUpdateRepository>()
                        val appRuntimeInfoRepository = mockk<AppRuntimeInfoRepository>()

                        coEvery { appRuntimeInfoRepository.getCurrentVersionName() } returns "2.0.0"
                        coEvery { appUpdateRepository.fetchLatestRelease() } returns
                            LatestAppRelease(
                                tagName = "v2.0.0",
                                htmlUrl = "https://example.com/releases/2.0.0",
                                body = "[FORCE_UPDATE]\nurgent fix",
                            )

                        val result =
                            CheckAppUpdateUseCase(
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldNotBe null
                        result?.version shouldBe "2.0.0"
                    }
        }
    }
}
