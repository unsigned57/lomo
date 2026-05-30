package com.lomo.data.repository

import com.lomo.data.testing.DataFunSpec
import com.lomo.domain.model.AppUpdateAssetUnsupportedReason
import com.lomo.domain.model.AppUpdateAssetVerification
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/*
 * Behavior Contract:
 * - Unit under test: parseLatestReleaseResponse
 * - Owning layer: data
 * - Priority tier: P0
 * - Capability: GitHub latest-release parsing emits typed APK asset candidates with verification status instead of treating the first APK asset as installable.
 *
 * Scenarios:
 * - Given a GitHub release asset named by the current release policy, when the release is parsed, then the APK becomes a verified candidate without fabricated GitHub JSON fields.
 * - Given a GitHub release asset named by the version-code release policy, when the release is parsed, then package, versionName, and versionCode metadata are derived from the file name.
 * - Given multiple APK assets and only a later one has matching package/version metadata, when the release is parsed, then the verified candidate is preserved with release metadata.
 * - Given an APK asset without a download URL, when the release is parsed, then it is represented as unsupported for missing download URL.
 * - Given an APK asset without package/version metadata, when the release is parsed, then it is represented as an unsupported candidate and no installable APK URL is exposed.
 * - Given an APK asset with wrong package metadata, when the release is parsed, then it is represented as unsupported for wrong package.
 * - Given an APK asset with matching package metadata but a version that does not match the release, when the release is parsed, then it is represented as unsupported for version mismatch.
 * - Given a release with no APK assets, when the release is parsed, then no candidates or installable APK fields are returned.
 *
 * Observable outcomes:
 * - Parsed release tag, release URL/body, APK candidate list, verification status, unsupported reason, download URL, file name, and size.
 *
 * TDD proof:
 * - RED was observed in the Batch 2C worker before the model/repository implementation was completed: the target data test could not compile because AppUpdateAssetVerification, AppUpdateAssetUnsupportedReason, and LatestAppRelease.assetCandidates did not exist.
 * - The old parser contract selected the first .apk asset directly, so wrong-package and missing-metadata scenarios could not expose typed unsupported reasons.
 *
 * Excludes:
 * - HTTP transport behavior, APK manifest extraction, checksum/signature validation, PackageInstaller integration, and UI state.
 *
 * Test Change Justification:
 * Reason category: Contract hardening for update safety.
 * Old behavior/assertion being replaced: The parser exposed the first .apk asset as apkDownloadUrl/apkFileName/apkSizeBytes.
 * Why old assertion is no longer correct: first-APK selection can route unverified or wrong-package assets toward installation.
 * Coverage preserved by: release metadata and verified APK metadata are still asserted when a candidate carries matching package/version metadata.
 * Why this is not fitting the test to the implementation: the new assertions encode the audit requirement that data emits typed candidates and domain filters installability.
 */
class AppUpdateReleaseParsingTest : DataFunSpec() {
    init {
        test("given current release apk asset without custom fields when parsing release then verified candidate is emitted") {
            val response =
                """
                {
                  "tag_name": "v1.5.1",
                  "html_url": "https://example.com/releases/1.5.1",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "lomo-v1.5.1.apk",
                      "browser_download_url": "https://example.com/assets/lomo-v1.5.1.apk",
                      "size": 4096
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            assertSoftly(release) {
                apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.5.1.apk"
                apkFileName shouldBe "lomo-v1.5.1.apk"
                apkSizeBytes shouldBe 4_096L
            }
            release.assetCandidates shouldHaveSize 1
            assertSoftly(release.assetCandidates.single()) {
                fileName shouldBe "lomo-v1.5.1.apk"
                downloadUrl shouldBe "https://example.com/assets/lomo-v1.5.1.apk"
                verification shouldBe
                    AppUpdateAssetVerification.Verified(
                        packageName = "com.lomo.app",
                        versionName = "1.5.1",
                        versionCode = null,
                    )
            }
        }

        test("given version-code release apk asset without custom fields when parsing release then version code is derived from file name") {
            val response =
                """
                {
                  "tag_name": "v1.5.1",
                  "html_url": "https://example.com/releases/1.5.1",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "lomo-v1.5.1-vc44.apk",
                      "browser_download_url": "https://example.com/assets/lomo-v1.5.1-vc44.apk",
                      "size": 4096
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            release.assetCandidates shouldHaveSize 1
            release.assetCandidates.single().verification shouldBe
                AppUpdateAssetVerification.Verified(
                    packageName = "com.lomo.app",
                    versionName = "1.5.1",
                    versionCode = 44L,
                )
        }

        test("given unsupported first apk and verified later apk when parsing release then verified candidate metadata is preserved") {
            val response =
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/releases/1.2.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "release-notes.txt",
                      "browser_download_url": "https://example.com/assets/release-notes.txt",
                      "size": 128
                    },
                    {
                      "name": "other-app-v1.2.0.apk",
                      "browser_download_url": "https://example.com/assets/other-app-v1.2.0.apk",
                      "size": 2048,
                      "lomo_package_name": "com.example.other",
                      "lomo_version_name": "1.2.0",
                      "lomo_version_code": 120
                    },
                    {
                      "name": "lomo-v1.2.0-vc120.apk",
                      "browser_download_url": "https://example.com/assets/lomo-v1.2.0-vc120.apk",
                      "size": 4096
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            assertSoftly(release) {
                tagName shouldBe "v1.2.0"
                htmlUrl shouldBe "https://example.com/releases/1.2.0"
                body shouldBe "Release notes"
                apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.2.0-vc120.apk"
                apkFileName shouldBe "lomo-v1.2.0-vc120.apk"
                apkSizeBytes shouldBe 4_096L
            }
            release.assetCandidates shouldHaveSize 2
            release.assetCandidates[0].verification shouldBe
                AppUpdateAssetVerification.Unsupported(AppUpdateAssetUnsupportedReason.WRONG_PACKAGE)
            assertSoftly(release.assetCandidates[1]) {
                fileName shouldBe "lomo-v1.2.0-vc120.apk"
                downloadUrl shouldBe "https://example.com/assets/lomo-v1.2.0-vc120.apk"
                sizeBytes shouldBe 4_096L
                verification shouldBe
                    AppUpdateAssetVerification.Verified(
                        packageName = "com.lomo.app",
                        versionName = "1.2.0",
                        versionCode = 120,
                    )
            }
        }

        test("given apk asset without package metadata when parsing release then candidate is unsupported and not installable") {
            val response =
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/releases/1.2.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "lomo-v.apk",
                      "browser_download_url": "https://example.com/assets/lomo-v.apk",
                      "size": 4096
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            release.apkDownloadUrl.shouldBeNull()
            release.apkFileName.shouldBeNull()
            release.apkSizeBytes.shouldBeNull()
            release.assetCandidates shouldHaveSize 1
            release.assetCandidates.single().verification shouldBe
                AppUpdateAssetVerification.Unsupported(AppUpdateAssetUnsupportedReason.METADATA_UNAVAILABLE)
        }

        test("given apk asset without download url when parsing release then candidate is unsupported") {
            val response =
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/releases/1.2.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "lomo-v1.2.0.apk",
                      "size": 4096,
                      "lomo_package_name": "com.lomo.app",
                      "lomo_version_name": "1.2.0",
                      "lomo_version_code": 120
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            release.apkDownloadUrl.shouldBeNull()
            release.assetCandidates shouldHaveSize 1
            assertSoftly(release.assetCandidates.single()) {
                downloadUrl.shouldBeNull()
                verification shouldBe
                    AppUpdateAssetVerification.Unsupported(AppUpdateAssetUnsupportedReason.DOWNLOAD_URL_MISSING)
            }
        }

        test("given apk asset with wrong package metadata when parsing release then candidate is unsupported") {
            val response =
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/releases/1.2.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "other-app-v1.2.0.apk",
                      "browser_download_url": "https://example.com/assets/other-app-v1.2.0.apk",
                      "size": 4096,
                      "lomo_package_name": "com.example.other",
                      "lomo_version_name": "1.2.0",
                      "lomo_version_code": 120
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            release.apkDownloadUrl.shouldBeNull()
            release.assetCandidates shouldHaveSize 1
            release.assetCandidates.single().verification shouldBe
                AppUpdateAssetVerification.Unsupported(AppUpdateAssetUnsupportedReason.WRONG_PACKAGE)
        }

        test("given apk asset with wrong version metadata when parsing release then candidate is unsupported") {
            val response =
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/releases/1.2.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "lomo-v1.1.0.apk",
                      "browser_download_url": "https://example.com/assets/lomo-v1.1.0.apk",
                      "size": 4096,
                      "lomo_package_name": "com.lomo.app",
                      "lomo_version_name": "1.1.0",
                      "lomo_version_code": 110
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            release.apkDownloadUrl.shouldBeNull()
            release.assetCandidates shouldHaveSize 1
            release.assetCandidates.single().verification shouldBe
                AppUpdateAssetVerification.Unsupported(AppUpdateAssetUnsupportedReason.VERSION_MISMATCH)
        }

        test("given release without apk assets when parsing release then no candidates are returned") {
            val response =
                """
                {
                  "tag_name": "v1.2.0",
                  "html_url": "https://example.com/releases/1.2.0",
                  "body": "Release notes",
                  "assets": [
                    {
                      "name": "release-notes.txt",
                      "browser_download_url": "https://example.com/assets/release-notes.txt",
                      "size": 128
                    }
                  ]
                }
                """.trimIndent()

            val release = parseLatestReleaseResponse(response)

            release.apkDownloadUrl.shouldBeNull()
            release.apkFileName.shouldBeNull()
            release.apkSizeBytes.shouldBeNull()
            release.assetCandidates shouldBe emptyList()
        }
    }
}
