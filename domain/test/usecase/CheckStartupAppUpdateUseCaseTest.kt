package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateAssetCandidate
import com.lomo.domain.model.AppUpdateAssetUnsupportedReason
import com.lomo.domain.model.AppUpdateAssetVerification
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeAppRuntimeInfoRepository
import com.lomo.domain.testing.fakes.FakeAppUpdateRepository
import com.lomo.domain.testing.fakes.FakePreferencesRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: CheckStartupAppUpdateUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: startup app update checks honor the user preference and expose installable updates only for verified newer APK candidates.
 *
 * Scenarios:
 * - Given startup checks are disabled, when startup update check runs, then release/runtime repositories are not queried.
 * - Given startup checks are enabled and a newer release has an unsupported first APK candidate plus a verified later candidate, when startup update check runs, then release notes and verified APK asset metadata are preserved.
 * - Given startup checks are enabled and a newer release has no verified APK candidate, when startup update check runs, then no installable update is returned.
 * - Given startup checks are enabled and a same-version release is forced in release notes, when startup update check runs, then the force marker does not bypass version suitability.
 *
 * Observable outcomes:
 * - Returned AppUpdateInfo or null, repository call gating, raw release notes, APK download URL, file name, and size.
 *
 * TDD proof:
 * - RED observed during Batch 2C with `./kotlin test --include-classes='com.lomo.domain.usecase.CheckStartupAppUpdateUseCaseTest'`.
 * - Before the shared evaluator was applied, startup checks could return update info for a newer release without a verified installable APK candidate and could let a force marker bypass same-version suitability.
 *
 * Excludes:
 * - GitHub HTTP transport, APK download bytes, PackageInstaller intents, checksum/signature validation, and UI presentation.
 *
 * Test Change Justification:
 * Reason category: Contract hardening for update safety.
 * Old behavior/assertion being replaced: Startup checks accepted force-marker release info without requiring a verified installable APK candidate.
 * Why old assertion is no longer correct: startup update discovery must not route unverified or same-version assets toward installation.
 * Coverage preserved by: raw release notes remain asserted in the verified newer-candidate scenario.
 * Why this is not fitting the test to the implementation: the new assertions encode the audit requirement that install paths only accept verified newer candidates.
 */
class CheckStartupAppUpdateUseCaseTest : DomainFunSpec() {
    init {
        test("given startup checks disabled when checking update then release and runtime repositories are skipped") {
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

                result.shouldBeNull()
                appUpdateRepository.fetchLatestReleaseCallCount shouldBe 0
                appRuntimeInfoRepository.getCurrentVersionNameCallCount shouldBe 0
            }
        }

        test("given startup checks enabled and unsupported first apk plus verified newer apk when checking update then verified metadata is preserved") {
            runTest {
                val rawNotes = "[FORCE_UPDATE]\r\n" + "A".repeat(3_500)
                val preferencesRepository = FakePreferencesRepository()
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v9.0.0",
                                body = rawNotes,
                                assetCandidates =
                                    listOf(
                                        AppUpdateAssetCandidate(
                                            fileName = "other-app-v9.0.0.apk",
                                            downloadUrl = "https://example.com/assets/other-app-v9.0.0.apk",
                                            sizeBytes = 16_384L,
                                            verification =
                                                AppUpdateAssetVerification.Unsupported(
                                                    AppUpdateAssetUnsupportedReason.WRONG_PACKAGE,
                                                ),
                                        ),
                                        verifiedCandidate(
                                            fileName = "lomo-v9.0.0.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v9.0.0.apk",
                                            sizeBytes = 16_384L,
                                            versionName = "9.0.0",
                                        ),
                                    ),
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
                assertSoftly(result!!) {
                    version shouldBe "9.0.0"
                    releaseNotes shouldBe rawNotes
                    apkDownloadUrl shouldBe "https://example.com/assets/lomo-v9.0.0.apk"
                    apkFileName shouldBe "lomo-v9.0.0.apk"
                    apkSizeBytes shouldBe 16_384L
                }
            }
        }

        test("given startup checks enabled and no verified apk candidate when checking update then no installable update is returned") {
            runTest {
                val preferencesRepository = FakePreferencesRepository()
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.1.0",
                                body = "asset is not for this package",
                                assetCandidates =
                                    listOf(
                                        AppUpdateAssetCandidate(
                                            fileName = "other-app-v1.1.0.apk",
                                            downloadUrl = "https://example.com/assets/other-app-v1.1.0.apk",
                                            sizeBytes = 4_096L,
                                            verification =
                                                AppUpdateAssetVerification.Unsupported(
                                                    AppUpdateAssetUnsupportedReason.WRONG_PACKAGE,
                                                ),
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.0.0")

                val result =
                    CheckStartupAppUpdateUseCase(
                        preferencesRepository = preferencesRepository,
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result.shouldBeNull()
            }
        }

        test("given startup checks enabled and same version force marker when checking update then no update is returned") {
            runTest {
                val preferencesRepository = FakePreferencesRepository()
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.2.3",
                                body = "[FORCE_UPDATE]\nNo newer version",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.2.3.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.2.3.apk",
                                            sizeBytes = 8_192L,
                                            versionName = "1.2.3",
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.2.3-debug")

                val result =
                    CheckStartupAppUpdateUseCase(
                        preferencesRepository = preferencesRepository,
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result.shouldBeNull()
            }
        }
    }
}

private fun latestRelease(
    tagName: String,
    body: String,
    assetCandidates: List<AppUpdateAssetCandidate>,
): LatestAppRelease =
    LatestAppRelease(
        tagName = tagName,
        htmlUrl = "https://example.com/release",
        body = body,
        assetCandidates = assetCandidates,
    )

private fun verifiedCandidate(
    fileName: String,
    downloadUrl: String,
    sizeBytes: Long,
    versionName: String,
): AppUpdateAssetCandidate =
    AppUpdateAssetCandidate(
        fileName = fileName,
        downloadUrl = downloadUrl,
        sizeBytes = sizeBytes,
        verification =
            AppUpdateAssetVerification.Verified(
                packageName = "com.lomo.app",
                versionName = versionName,
            ),
    )
