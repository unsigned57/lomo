package com.lomo.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: parseLatestReleaseResponse
 * - Behavior focus: GitHub latest-release parsing preserves release metadata and extracts the downloadable APK asset needed for in-app updates.
 * - Observable outcomes: parsed version tag, release URL/body, and selected APK asset fields.
 * - Red phase: Fails before the fix because latest-release parsing only keeps tag/url/body and does not expose any APK download asset for the in-app updater.
 * - Excludes: HTTP transport behavior, ViewModel state management, and package-installer integration.
 */
class AppUpdateReleaseParsingTest {
    @Test
    fun `parseLatestReleaseResponse captures first apk asset for in-app download`() {
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

        assertEquals("v1.2.0", release.tagName)
        assertEquals("https://example.com/releases/1.2.0", release.htmlUrl)
        assertEquals("Release notes", release.body)
        assertEquals("https://example.com/assets/lomo-v1.2.0.apk", release.apkDownloadUrl)
        assertEquals("lomo-v1.2.0.apk", release.apkFileName)
        assertEquals(4096L, release.apkSizeBytes)
    }

    @Test
    fun `parseLatestReleaseResponse leaves apk fields empty when release has no apk asset`() {
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

        assertNull(release.apkDownloadUrl)
        assertNull(release.apkFileName)
        assertNull(release.apkSizeBytes)
    }
}
