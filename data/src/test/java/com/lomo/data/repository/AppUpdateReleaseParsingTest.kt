package com.lomo.data.repository

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



import com.lomo.data.testing.DataFunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/*
 * Behavior Contract:
 * - Unit under test: parseLatestReleaseResponse
 * - Behavior focus: GitHub latest-release parsing preserves release metadata and extracts the downloadable APK asset needed for in-app updates.
 * - Observable outcomes: parsed version tag, release URL/body, and selected APK asset fields.
 * - TDD proof: Fails before the fix because latest-release parsing only keeps tag/url/body and does not expose any APK download asset for the in-app updater.
 * - Excludes: HTTP transport behavior, ViewModel state management, and package-installer integration.
 */
class AppUpdateReleaseParsingTest : DataFunSpec() {
    init {
        test("parseLatestReleaseResponse captures first apk asset for in-app download") { `parseLatestReleaseResponse captures first apk asset for in-app download`() }

        test("parseLatestReleaseResponse leaves apk fields empty when release has no apk asset") { `parseLatestReleaseResponse leaves apk fields empty when release has no apk asset`() }
    }


    private fun `parseLatestReleaseResponse captures first apk asset for in-app download`() {
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
                  "name": "lomo-v1.2.0.apk",
                  "browser_download_url": "https://example.com/assets/lomo-v1.2.0.apk",
                  "size": 4096
                }
              ]
            }
            """.trimIndent()

        val release = parseLatestReleaseResponse(response)

        release.tagName shouldBe "v1.2.0"
        release.htmlUrl shouldBe "https://example.com/releases/1.2.0"
        release.body shouldBe "Release notes"
        release.apkDownloadUrl shouldBe "https://example.com/assets/lomo-v1.2.0.apk"
        release.apkFileName shouldBe "lomo-v1.2.0.apk"
        release.apkSizeBytes shouldBe 4096L
    }

    private fun `parseLatestReleaseResponse leaves apk fields empty when release has no apk asset`() {
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
    }
}
