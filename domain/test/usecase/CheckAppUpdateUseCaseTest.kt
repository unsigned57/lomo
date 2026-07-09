package com.lomo.domain.usecase

import com.lomo.domain.model.AppUpdateAssetCandidate
import com.lomo.domain.model.AppUpdateAssetUnsupportedReason
import com.lomo.domain.model.AppUpdateAssetVerification
import com.lomo.domain.model.LatestAppRelease
import com.lomo.domain.testing.DomainFunSpec
import com.lomo.domain.testing.fakes.FakeAppRuntimeInfoRepository
import com.lomo.domain.testing.fakes.FakeAppUpdateRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

/*
 * Behavior Contract:
 * - Unit under test: CheckAppUpdateUseCase
 * - Owning layer: domain
 * - Priority tier: P0
 * - Capability: manual app update checks return installable update info only for verified newer APK candidates.
 *
 * Scenarios:
 * - Given a newer release where the first APK candidate is unsupported and a later candidate is verified, when manual update check runs, then release notes and verified APK asset metadata are preserved.
 * - Given a verified newer APK candidate whose metadata version keeps the release-tag v prefix, when manual update check runs, then the update is still returned.
 * - Given a same-name APK candidate with a higher versionCode, when manual update check runs, then Android upgrade suitability is based on versionCode and the update is returned.
 * - Given a newer display version whose APK versionCode is not greater than the installed versionCode, when manual update check runs, then the update is rejected before install.
 * - Given a verified APK candidate without versionCode metadata and the installed versionCode is known, when manual update check runs, then the candidate is rejected as unverifiable.
 * - Given a verified APK candidate without versionCode metadata and the installed versionCode is unavailable, when manual update check runs, then versionName remains the compatibility fallback for old environments.
 * - Given a newer release without an APK candidate, when manual update check runs, then no installable update is returned.
 * - Given a same-version or downgrade release, when manual update check runs, then no update is returned even if the APK candidate is verified.
 * - Given a newer release whose APK candidate is unsupported because package metadata is wrong, when manual update check runs, then the candidate does not enter the install path.
 *
 * Observable outcomes:
 * - Returned AppUpdateInfo or null, selected version, release notes, APK download URL, file name, and size.
 *
 * TDD proof:
 * - RED observed with `./kotlin test --include-classes='com.lomo.domain.usecase.CheckAppUpdateUseCaseTest'`.
 * - The v-prefixed candidate scenario failed because evaluateAppUpdate compared verified.versionName=`v1.1.0` directly, parsing `v1` as 0 and filtering a valid newer release.
 * - RED observed during this follow-up because the known installed versionCode plus missing candidate versionCode scenario returned AppUpdateInfo instead of null.
 *
 * Excludes:
 * - GitHub HTTP transport, APK download bytes, PackageInstaller intents, checksum/signature validation, and UI presentation.
 *
 * Test Change Justification:
 * Reason category: Contract hardening for update safety.
 * Old behavior/assertion being replaced: A force marker could return update info for the same version without an installable APK candidate.
 * Why old assertion is no longer correct: update discovery must not route same-version, downgrade, or unverified assets toward installation.
 * Coverage preserved by: release notes are still preserved in the verified newer-candidate scenario.
 * Why this is not fitting the test to the implementation: the new assertions encode the audit requirement that install paths only accept verified newer candidates.
 */
class CheckAppUpdateUseCaseTest : DomainFunSpec() {
    init {
        test("given newer release with unsupported first apk and verified later apk when checking manually then verified metadata is preserved") {
            runTest {
                val release =
                    latestRelease(
                        tagName = "v1.1.0",
                        body = "new release",
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
                                verifiedCandidate(
                                    fileName = "lomo-v1.1.0.apk",
                                    downloadUrl = "https://example.com/assets/lomo-v1.1.0.apk",
                                    sizeBytes = 4_096L,
                                    versionName = "1.1.0",
                                ),
                            ),
                    )
                val appUpdateRepository = FakeAppUpdateRepository(latestRelease = release)
                val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.0.0-debug")

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result shouldNotBe null
                assertSoftly(result!!) {
                    version shouldBe "1.1.0"
                    url shouldBe "https://example.com/releases/1.1.0"
                    releaseNotes shouldBe "new release"
                    apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.1.0.apk"
                    apkFileName shouldBe "lomo-v1.1.0.apk"
                    apkSizeBytes shouldBe 4_096L
                }
            }
        }

        test("given newer release without apk candidate when checking manually then no installable update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.1.0",
                                body = "new release without apk",
                                assetCandidates = emptyList(),
                            ),
                    )
                val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.0.0")

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result.shouldBeNull()
            }
        }

        test("given verified candidate version keeps v prefix when checking manually then update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.1.0",
                                body = "new release",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.1.0.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.1.0.apk",
                                            sizeBytes = 4_096L,
                                            versionName = "v1.1.0",
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.0.0")

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result shouldNotBe null
                result!!.version shouldBe "1.1.0"
                result.apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.1.0.apk"
            }
        }

        test("given same display version with higher apk version code when checking manually then update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.5.1",
                                body = "new build",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.5.1-44.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.5.1-44.apk",
                                            sizeBytes = 4_096L,
                                            versionName = "1.5.1",
                                            versionCode = 44L,
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository =
                    FakeAppRuntimeInfoRepository(
                        currentVersionName = "1.5.1",
                        currentVersionCode = 43L,
                    )

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result shouldNotBe null
                assertSoftly(result!!) {
                    version shouldBe "1.5.1"
                    apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.5.1-44.apk"
                    expectedPackageName shouldBe "com.lomo.app"
                    expectedVersionName shouldBe "1.5.1"
                    expectedVersionCode shouldBe 44L
                }
            }
        }

        test("given newer display version with non-upgrade apk version code when checking manually then no update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.6.0",
                                body = "bad build code",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.6.0-43.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.6.0-43.apk",
                                            sizeBytes = 4_096L,
                                            versionName = "1.6.0",
                                            versionCode = 43L,
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository =
                    FakeAppRuntimeInfoRepository(
                        currentVersionName = "1.5.1",
                        currentVersionCode = 43L,
                    )

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result.shouldBeNull()
            }
        }

        test("given known installed version code and candidate without version code when checking manually then no update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.6.0",
                                body = "current artifact naming",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.6.0.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.6.0.apk",
                                            sizeBytes = 4_096L,
                                            versionName = "1.6.0",
                                            versionCode = null,
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository =
                    FakeAppRuntimeInfoRepository(
                        currentVersionName = "1.5.1",
                        currentVersionCode = 43L,
                    )

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result.shouldBeNull()
            }
        }

        test("given installed version code unavailable and candidate without version code when checking manually then version name fallback returns update") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.6.0",
                                body = "old environment artifact naming",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.6.0.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.6.0.apk",
                                            sizeBytes = 4_096L,
                                            versionName = "1.6.0",
                                            versionCode = null,
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository =
                    FakeAppRuntimeInfoRepository(
                        currentVersionName = "1.5.1",
                        currentVersionCode = null,
                    )

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result shouldNotBe null
                assertSoftly(result!!) {
                    version shouldBe "1.6.0"
                    apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.6.0.apk"
                    expectedVersionCode.shouldBeNull()
                }
            }
        }

        test("given same or downgrade release with verified apk when checking manually then no update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v1.2.2",
                                body = "[FORCE_UPDATE]\nolder release",
                                assetCandidates =
                                    listOf(
                                        verifiedCandidate(
                                            fileName = "lomo-v1.2.2.apk",
                                            downloadUrl = "https://example.com/assets/lomo-v1.2.2.apk",
                                            sizeBytes = 8_192L,
                                            versionName = "1.2.2",
                                        ),
                                    ),
                            ),
                    )
                val appRuntimeInfoRepository = FakeAppRuntimeInfoRepository("1.2.3")

                val result =
                    CheckAppUpdateUseCase(
                        appUpdateRepository = appUpdateRepository,
                        appRuntimeInfoRepository = appRuntimeInfoRepository,
                    ).invoke()

                result.shouldBeNull()
            }
        }

        test("given newer release with wrong package apk metadata when checking manually then no installable update is returned") {
            runTest {
                val appUpdateRepository =
                    FakeAppUpdateRepository(
                        latestRelease =
                            latestRelease(
                                tagName = "v2.0.0",
                                body = "wrong package asset",
                                assetCandidates =
                                    listOf(
                                        AppUpdateAssetCandidate(
                                            fileName = "other-app-v2.0.0.apk",
                                            downloadUrl = "https://example.com/assets/other-app-v2.0.0.apk",
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
                    CheckAppUpdateUseCase(
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
        htmlUrl = "https://example.com/releases/${tagName.removePrefix("v")}",
        body = body,
        assetCandidates = assetCandidates,
    )

private fun verifiedCandidate(
    fileName: String,
    downloadUrl: String,
    sizeBytes: Long,
    versionName: String,
    versionCode: Long? = null,
): AppUpdateAssetCandidate =
    AppUpdateAssetCandidate(
        fileName = fileName,
        downloadUrl = downloadUrl,
        sizeBytes = sizeBytes,
        verification =
            AppUpdateAssetVerification.Verified(
                packageName = "com.lomo.app",
                versionName = versionName,
                versionCode = versionCode,
            ),
    )
