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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: CheckAppUpdateUseCase
 * - Behavior focus: unconditional release lookup, semantic version comparison, and force-update override for manual checks.
 * - Observable outcomes: returned AppUpdateInfo or null, selected remote version, and preserved release metadata when a newer release exists.
 * - TDD proof: Fails before the fix because manual update checking does not exist as a domain capability yet.
 * - Excludes: preferences gating, repository transport implementation, and UI presentation normalization.
 */
class CheckAppUpdateUseCaseTest : DomainFunSpec() {
    init {
        test("invoke returns update info when remote semantic version is newer") {
            runTest {
                        val appUpdateRepository =
                            FakeAppUpdateRepository(
                                latestRelease = LatestAppRelease(
                                tagName = "v1.1.0",
                                htmlUrl = "https://example.com/releases/1.1.0",
                                body = "new release",
                                ),
                            )
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.0.0-debug")

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

        test("invoke returns null when remote version is not newer and force marker is absent") {
            runTest {
                        val appUpdateRepository =
                            FakeAppUpdateRepository(
                                latestRelease = LatestAppRelease(
                                tagName = "v1.2.3",
                                htmlUrl = "https://example.com/releases/1.2.3",
                                body = "same version",
                                ),
                            )
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.2.3")

                        val result =
                            CheckAppUpdateUseCase(
                                appUpdateRepository = appUpdateRepository,
                                appRuntimeInfoRepository = appRuntimeInfoRepository,
                            ).invoke()

                        result shouldBe null
                    }
        }

        test("invoke returns update info when force marker is present even if versions match") {
            runTest {
                        val appUpdateRepository =
                            FakeAppUpdateRepository(
                                latestRelease = LatestAppRelease(
                                tagName = "v2.0.0",
                                htmlUrl = "https://example.com/releases/2.0.0",
                                body = "[FORCE_UPDATE]\nurgent fix",
                                ),
                            )
                        val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("2.0.0")

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
