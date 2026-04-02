package com.lomo.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Test Contract:
 * - Unit under test: sanitizedDetail in SettingsErrorPresenter.kt
 * - Behavior focus: S3 settings error presentation should keep actionable diagnostic lines while dropping bare exception-class noise.
 * - Observable outcomes: returned sanitized detail string for user-visible settings errors.
 * - Red phase: Fails before the fix because a first-line exception wrapper causes sanitizedDetail to discard the second-line transport detail completely.
 * - Excludes: Compose rendering, string-resource formatting, coordinator state machines, and transport execution.
 */
class SettingsErrorPresenterDetailSanitizerTest {
    @Test
    fun `sanitizedDetail falls back to later actionable line when first line is exception wrapper`() {
        val detail =
            invokeSanitizedDetail(
                """
                java.lang.IllegalStateException: S3 sync failed
                TLS handshake timed out while connecting to https://s3.example.com
                """.trimIndent(),
            )

        assertEquals(
            "TLS handshake timed out while connecting to https://s3.example.com",
            detail,
        )
    }

    @Test
    fun `sanitizedDetail drops bare exception class noise`() {
        val detail = invokeSanitizedDetail("java.net.SocketTimeoutException")

        assertNull(detail)
    }

    private fun invokeSanitizedDetail(rawDetail: String?): String? {
        val method =
            Class
                .forName("com.lomo.app.feature.settings.SettingsErrorPresenterKt")
                .getDeclaredMethod("sanitizedDetail", String::class.java)
        method.isAccessible = true
        return method.invoke(null, rawDetail) as String?
    }
}
