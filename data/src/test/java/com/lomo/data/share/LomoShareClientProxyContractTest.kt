package com.lomo.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Test Contract:
 * - Unit under test: LAN share HTTP client transport configuration.
 * - Behavior focus: transfers to discovered LAN addresses must bypass the system HTTP proxy so
 *   proxy settings cannot intercept or block local peer requests.
 * - Observable outcomes: source-level contract that the OkHttp engine configures Proxy.NO_PROXY.
 * - Red phase: Fails before the fix because LomoShareClient uses OkHttp defaults, which can honor
 *   the device/system proxy for LAN peer URLs.
 * - Excludes: live proxy behavior, Ktor internals, and share request validation.
 */
class LomoShareClientProxyContractTest {
    @Test
    fun `share client bypasses system proxy for LAN peer requests`() {
        val source = File("src/main/java/com/lomo/data/share/LomoShareClient.kt").readText()

        assertTrue(
            "LAN share OkHttp client must configure Proxy.NO_PROXY.",
            source.contains("Proxy.NO_PROXY") && source.contains("proxy(Proxy.NO_PROXY)"),
        )
    }

    @Test
    fun `share client uses fast fail LAN timeouts`() {
        assertEquals(5_000L, LomoShareClient.CONNECT_TIMEOUT_MS)
        assertEquals(3_000L, LomoShareClient.PING_TIMEOUT_MS)
        assertEquals(30_000L, LomoShareClient.REQUEST_TIMEOUT_MS)
    }
}
