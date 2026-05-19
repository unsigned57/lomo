package com.lomo.domain.usecase

/**
 * Behavior Contract:
 * Capability: Kotest Migration
 * Scenarios: Given standard test execution, when tests run, then assertions hold.
 * Observable outcomes: Green tests
 * TDD proof: Compilation failure on Kotest transition
 * Excludes: none
 * 
 * Test Change Justification:
 * Reason category: Migration
 * Old behavior/assertion being replaced: JUnit4 assertions
 * Why old assertion is no longer correct: Transitioning to Kotest
 * Coverage preserved by: Kotest functional matching
 * Why this is not fitting the test to the implementation: Syntax translation
 */


import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeAppRuntimeInfoRepository
import com.lomo.domain.testing.fakes.FakeAppUpdateRepository
import com.lomo.domain.testing.fakes.FakePreferencesRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: CheckStartupAppUpdateUseCase
 * - Behavior focus: startup update gating, force-update override, semantic version comparison, and fallback comparison for malformed versions.
 * - Observable outcomes: returned AppUpdateInfo or null, preserved release notes, and repository call gating when startup checks are disabled.
 * - TDD proof: Fails before behavior changes or migration are applied.
 * - Excludes: repository transport implementation, UI presentation, and release-note rendering.
 */
class CheckStartupAppUpdateUseCaseTest : DomainFunSpec() {
    init {
        test("invoke keeps raw release notes without presentation normalization") {
            runTest {
                        val rawNotes = "[FORCE_UPDATE]\r\n" + "A".repeat(3500)
                        val preferencesRepository = FakePreferencesRepository()
                        val appUpdateRepository =
                            FakeAppUpdateRepository(
                                latestRelease = LatestAppRelease(
                                tagName = "v9.0.0",
                                htmlUrl = "https://example.com/release",
                                body = rawNotes,
                                ),
                            )
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("0.1.0")

                        val result =
                            CheckStartupAppUpdateUseCase(
                                preferencesRepository = preferencesRepository,
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldNotBe null
                        result?.releaseNotes shouldBe rawNotes
                    }
        }

        test("invoke returns null and skips fetch when startup update checks are disabled") {
            runTest {
                        val preferencesRepository = FakePreferencesRepository()
                        val appUpdateRepository = FakeAppUpdateRepository()
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository()
                        preferencesRepository.setCheckUpdatesOnStartupEnabled(false)

                        val result =
                            CheckStartupAppUpdateUseCase(
                                preferencesRepository = preferencesRepository,
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldBe null
                        appUpdateRepository.fetchLatestReleaseCallCount shouldBe 0
                        appRuntimeInfoRepository.getCurrentVersionNameCallCount shouldBe 0
                    }
        }

        test("invoke returns null when remote version is not newer and no force marker is present") {
            runTest {
                        val preferencesRepository = FakePreferencesRepository()
                        val appUpdateRepository =
                            FakeAppUpdateRepository(
                                latestRelease = LatestAppRelease(
                                tagName = "v1.2.3",
                                htmlUrl = "https://example.com/release",
                                body = "No force update",
                                ),
                            )
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.2.3-debug")

                        val result =
                            CheckStartupAppUpdateUseCase(
                                preferencesRepository = preferencesRepository,
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldBe null
                    }
        }

        test("invoke returns update info when remote semantic version is newer") {
            runTest {
                        val preferencesRepository = FakePreferencesRepository()
                        val appUpdateRepository =
                            FakeAppUpdateRepository(
                                latestRelease = LatestAppRelease(
                                tagName = "v1.1.0",
                                htmlUrl = "https://example.com/release",
                                body = "new release",
                                ),
                            )
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.0.0")

                        val result =
                            CheckStartupAppUpdateUseCase(
                                preferencesRepository = preferencesRepository,
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldNotBe null
                        result?.version shouldBe "1.1.0"
                    }
        }
    }
}
